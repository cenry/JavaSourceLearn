/*
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

/*
 *
 *
 *
 *
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent;

import java.io.ObjectStreamField;
import java.io.Serializable;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Spliterator;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
import java.util.function.DoubleBinaryOperator;
import java.util.function.Function;
import java.util.function.IntBinaryOperator;
import java.util.function.LongBinaryOperator;
import java.util.function.ToDoubleBiFunction;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntBiFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongBiFunction;
import java.util.function.ToLongFunction;
import java.util.stream.Stream;

/**
 * @since 1.5
 * @author Doug Lea
 * @param <K> the type of keys maintained by this map
 * @param <V> the type of mapped values
 */
public class ConcurrentHashMap<K,V> extends AbstractMap<K,V>
    implements ConcurrentMap<K,V>, Serializable {
    private static final long serialVersionUID = 7249069246763182397L;

    /* ---------------- Constants -------------- */

    /**
     * 数组的最大容量，这个值必须是1 << 30，以同时满足两个条件：
     * 1. 小于Java数组的最大容量(1 << 31 - 1)
     * 2. 是2的整数次幂
     * 更进一步地说，32位的哈希字段的最高两位需要被用作其他控制
     */
    private static final int MAXIMUM_CAPACITY = 1 << 30;

    /**
     * 默认的初始容量，必须是2的整数次幂（至少是1），最大是MAXIMUM_CAPACITY
     */
    private static final int DEFAULT_CAPACITY = 16;

    /**
     * toArray与其相关方法返回的数组的最大长度。（不是2的整数次幂）
     */
    static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;

    /**
     * 该哈希表默认的并发级别。目前已没有使用，但为了兼容本类的早期版本需要保留。
     */
    private static final int DEFAULT_CONCURRENCY_LEVEL = 16;

    /**
     * 本哈希表的负载因子，在构造器中重写负载因子只对初始化数组容量有效，此后将不再使用这个字段。
     * 之后扩容的时候会使用n - (n >>> 2)这样的表达式去重新计算扩容门槛。
     */
    private static final float LOAD_FACTOR = 0.75f;

    /**
     * 容器由链表转为树结构的临界值，默认的，如果容器中现在有7个元素，下一个放入该容器的元素将导致容器结构由链表转为树。
     * 为了与元素数量减少时容器结构转回链表的临界值契合，这个值必须大于2并且至少是8。
     */
    static final int TREEIFY_THRESHOLD = 8;

    /**
     * 元素数量减少时容器结构转回链表的临界值，默认的，如果容器现在是树结构，且移除一个元素后刚好有6个元素，那么这个容器将转回链表。
     * 这个值要小于TREEIFY_THRESHOLD，此外，为了避免链表和树结构的频繁转换，最大只能定为6。
     */
    static final int UNTREEIFY_THRESHOLD = 6;

    /**
     * table容量至少需要达到这个值，才会在单个容器元素过多时转红黑树，否则元素数量达到TREEIFY_THRESHOLD时首先考虑的是将整个table扩容。
     * 这个值至少是4 * TREEIFY_THRESHOLD，以避免扩容阈值和树结构化的门槛值发生冲突。
     */
    static final int MIN_TREEIFY_CAPACITY = 64;

    /**
     * 扩容时每个转化步骤的步长的最小值。转化任务可以细分给多个线程一起操作，这个最小值避免了过度的内存争夺。
     * 这个值最小应该是DEFAULT_CAPACITY的值。
     */
    private static final int MIN_TRANSFER_STRIDE = 16;

    /**
     * 为sizeCtl生成扩容戳所使用的位数。
     * 在32位数组中必须至少是6。
     */
    private static int RESIZE_STAMP_BITS = 16;

    /**
     * 可以帮助扩容的最大的线程数量。必须在32 - RESIZE_STAMP_BITS位以内。
     */
    private static final int MAX_RESIZERS = (1 << (32 - RESIZE_STAMP_BITS)) - 1;

    /**
     * 为了记录扩容戳所需要移动的位数。
     * 它和RESIZE_STAMP_BITS必须是和为32的关系，详情请参考扩容部分的代码注释。
     */
    private static final int RESIZE_STAMP_SHIFT = 32 - RESIZE_STAMP_BITS;

    /*
     * 几种特殊节点的哈希值
     */
    static final int MOVED     = -1; // 转移节点（不包含键值）
    static final int TREEBIN   = -2; // 树节点的根节点（不包含键值）
    static final int RESERVED  = -3; // compute及computeIfAbsent中使用的占位节点
    static final int HASH_BITS = 0x7fffffff; // 普通节点哈希值的有效位数

    /** CPU数量，在某些地方（如扩容时）用来设置限制值 */
    static final int NCPU = Runtime.getRuntime().availableProcessors();

    /** 用于兼容序列化 */
    private static final ObjectStreamField[] serialPersistentFields = {
        new ObjectStreamField("segments", Segment[].class),
        new ObjectStreamField("segmentMask", Integer.TYPE),
        new ObjectStreamField("segmentShift", Integer.TYPE)
    };

    /* ---------------- 节点 -------------- */

    /**
     * 键-值对条目。这个类从不会作为用户可变的Map.Entry对外暴露（比如使用setValue是会直接抛出异常的），
     * 但可以在批量任务中被当作被用作只读的遍历。Node的hash字段为负数的子类是特别的，它们也有可能包含null键或null值，但不会对外暴露。
     * 在其他情况下，key和value始终不会为null。
     */
    static class Node<K,V> implements Map.Entry<K,V> {
        final int hash;
        final K key;
        volatile V val;
        volatile Node<K,V> next;

        Node(int hash, K key, V val, Node<K,V> next) {
            this.hash = hash;
            this.key = key;
            this.val = val;
            this.next = next;
        }

        public final K getKey()       { return key; }
        public final V getValue()     { return val; }
        public final int hashCode()   { return key.hashCode() ^ val.hashCode(); }
        public final String toString(){ return key + "=" + val; }
        public final V setValue(V value) {
            throw new UnsupportedOperationException();
        }

        public final boolean equals(Object o) {
            Object k, v, u; Map.Entry<?,?> e;
            return ((o instanceof Map.Entry) &&
                    (k = (e = (Map.Entry<?,?>)o).getKey()) != null &&
                    (v = e.getValue()) != null &&
                    (k == key || k.equals(key)) &&
                    (v == (u = val) || v.equals(u)));
        }

        /**
         * 为map.get()提供虚拟的支持，在子类中被重写
         */
        Node<K,V> find(int h, Object k) {
            Node<K,V> e = this;
            if (k != null) {
                do {
                    K ek;
                    if (e.hash == h &&
                        ((ek = e.key) == k || (ek != null && k.equals(ek))))
                        return e;
                } while ((e = e.next) != null);
            }
            return null;
        }
    }

    /* ---------------- 静态工具方法 -------------- */

    /**
     * 将高16位通过异或操作散布到低16位中去，并强制将最高位（符号位）置0。因为下标计算和数组的长度有关，
     * 哈希值的高位总是被屏蔽掉，所以用这样一个扰动函数尽可能使低位产生一些区别，以减少哈希碰撞。
     * 由于我们使用树结构处理过多的哈希冲突，所以仅仅是用了一个简单的异或操作作为扰动函数以减少额外的性能开销。
     * 由于数组最大长度的限制，哈希值的最高位从来不会影响下标计算结果，所以我们直接将它屏蔽了。
     */
    static final int spread(int h) {
        return (h ^ (h >>> 16)) & HASH_BITS;
    }

    /**
     * 返回一个大于等于输入值的最小的2的整数次幂的值。
     */
    private static final int tableSizeFor(int c) {
        int n = c - 1;
        n |= n >>> 1;
        n |= n >>> 2;
        n |= n >>> 4;
        n |= n >>> 8;
        n |= n >>> 16;
        return (n < 0) ? 1 : (n >= MAXIMUM_CAPACITY) ? MAXIMUM_CAPACITY : n + 1;
    }

    /**
     * 如果x类是"class C implements Comparable<C>"形式的，则返回其类型，否则返回null。
     * 另外再提一下Type[] java.lang.Class.getGenericInterfaces()方法，它返回实现接口信息的Type数组，包含泛型信息。
     */
    static Class<?> comparableClassFor(Object x) {
        if (x instanceof Comparable) {
            Class<?> c; Type[] ts, as; Type t; ParameterizedType p;
            if ((c = x.getClass()) == String.class) // 是字符串，肯定是实现Comparable接口的。
                return c;
            if ((ts = c.getGenericInterfaces()) != null) {
                for (int i = 0; i < ts.length; ++i) {
                    if (((t = ts[i]) instanceof ParameterizedType) &&
                        ((p = (ParameterizedType)t).getRawType() ==
                         Comparable.class) &&
                        (as = p.getActualTypeArguments()) != null &&
                        as.length == 1 && as[0] == c) // 实现了Comparable接口且泛型类是它自己。
                        return c;
                }
            }
        }
        return null;
    }

    /**
     * 如果x是kc（kc是k经检测后确定的comparable类）类型的，则返回k.compareTo(x)的结果，否则返回0。
     */
    @SuppressWarnings({"rawtypes","unchecked"}) // 为了强制转换Comparable。
    static int compareComparables(Class<?> kc, Object k, Object x) {
        return (x == null || x.getClass() != kc ? 0 :
                ((Comparable)k).compareTo(x));
    }

    /* ---------------- 数组元素访问 -------------- */

    /*
     * 三个线程安全的数组访问方法，如果正在进行扩容，则会正确地访问到新数组的对应位置。它们的调用者必须检查参数不能为null，
     * 另外需要断定数组长度不为0，从而确保任何以hash &（length-1）计算得到的索引都是有效的索引。
     * 注意，为了纠正使用者的任意并发错误，这些检查必须对局部变量进行操作，这些变量解释了下面一些奇怪的内联赋值。
     * 注意，对setTabAt的调用总是发生在锁定的区域内，因此原则上只需要发布顺序，而不需要完整的volatile语义，
     * 但当前编码为volatile写操作是保守的。
     */

    /**
     * 获取tab数组对应下标i的元素。
     * 按照最下方推出的Integer得到ASHIFT = 2，假如i = 3，那么i << ASHIFT = 3 << 2 = 3 * (2 ^ 2) = 12，
     * 即从数组起始位置ABASE偏移12字节，刚好3个Integer元素，偏移指针指向第四个元素，这样就得到了下标为3的那个元素。
     *
     * 另外getObjectVolatile方法取值和volatile关键字效果一样，线程之间是可见的，
     * 假如线程A获取了下标为3的那个元素，然后将其放到CPU高速缓存中，在CPU指令处理这个元素之前，线程B修改了这个元素的值，
     * 那么修改后的值会被同步到CPU高速缓存中，从而解决了并发读写问题。
     */
    @SuppressWarnings("unchecked")
    static final <K,V> Node<K,V> tabAt(Node<K,V>[] tab, int i) {
        return (Node<K,V>)U.getObjectVolatile(tab, ((long)i << ASHIFT) + ABASE);
    }

    /**
     * 通过CAS操作向tab数组的i下标位置放入v，前提是放入的前一刻，该位置的值是c。
     * 在本类中使用casTabAt传入的c都是null，即尝试向空的bin放入节点时才用CAS操作。
     */
    static final <K,V> boolean casTabAt(Node<K,V>[] tab, int i,
                                        Node<K,V> c, Node<K,V> v) {
        return U.compareAndSwapObject(tab, ((long)i << ASHIFT) + ABASE, c, v);
    }

    /**
     * 如果放入的不是bin中的第一个节点，则使用这个方法。同样也是线程之间可见的。
     */
    static final <K,V> void setTabAt(Node<K,V>[] tab, int i, Node<K,V> v) {
        U.putObjectVolatile(tab, ((long)i << ASHIFT) + ABASE, v);
    }

    /* ---------------- Fields -------------- */

    /**
     * bin数组，将在首次插入元素时进行初始化，长度总是2的整数次幂，被迭代器直接存取。
     */
    transient volatile Node<K,V>[] table;

    /**
     * 下一个被使用的数组，只在扩容过程中是非null的。
     */
    private transient volatile Node<K,V>[] nextTable;

    /**
     * 基础的计数器，主要在没有竞争的时候使用，在初始化时也被当作是后备计划。
     * 通过CAS操作更新。
     */
    private transient volatile long baseCount;

    /**
     * 数组初始化和扩容控制。当它是负值的时候，代表数组正在被初始化或扩容：-1表示初始化，其他的-(1 + 活跃的扩容线程数)表示扩容。
     * 当数组还是null时，它持有初始化数组长度，以便在创建数组时使用，或者默认情况下是0。
     * 在初始化后，它持有下次扩容的目标容量值。
     */
    private transient volatile int sizeCtl;

    /**
     * 扩容时使用的，分割的下一个数组下标(+1)
     */
    private transient volatile int transferIndex;

    /**
     * 扩容和/或创建CounterCell时使用的自旋锁（通过CAS锁定）
     */
    private transient volatile int cellsBusy;

    /**
     * 持有计数器单元格的数组，当它不是null的时候，长度是2的整数次幂
     */
    private transient volatile CounterCell[] counterCells;

    // 视图
    private transient KeySetView<K,V> keySet;
    private transient ValuesView<K,V> values;
    private transient EntrySetView<K,V> entrySet;


    /* ---------------- Public operations -------------- */

    /**
     * 使用默认的初始容量(16)创建一个新的，空的map。
     */
    public ConcurrentHashMap() {
    }

    /**
     * 使用一个初始的数组长度（确保可以容纳给定数量的节点，不需要动态扩容）创建一个新的，空的map。
     *
     * @param initialCapacity 预计想要放入的节点数量
     * @throws IllegalArgumentException 如果指定的初始容量为负数
     */
    public ConcurrentHashMap(int initialCapacity) {
        if (initialCapacity < 0)
            throw new IllegalArgumentException();
        int cap = ((initialCapacity >= (MAXIMUM_CAPACITY >>> 1)) ?
                   MAXIMUM_CAPACITY :
                   tableSizeFor(initialCapacity + (initialCapacity >>> 1) + 1));
        this.sizeCtl = cap; // 计算好，放入initialCapacity个节点刚好不会达到扩容阈值
    }

    /**
     * 创建一个和给定map含有相同节点的新的map。
     *
     * @param m 给定的map
     */
    public ConcurrentHashMap(Map<? extends K, ? extends V> m) {
        this.sizeCtl = DEFAULT_CAPACITY;
        putAll(m);
    }

    /**
     * 创建一个新的，空的map，根据给定的元素数量计算出初始数组长度，并根据传入的负载因子以对应密度初始化数组。
     *
     * @param initialCapacity 预计想要放入的节点数量，构造器内部会根据数量刚好不会扩容计算数组长度
     * @param loadFactor 为初始化数组长度提供的负载因子（数组密度）
     * @throws IllegalArgumentException 如果容量为负数或负载因子为非整数
     *
     * @since 1.6
     */
    public ConcurrentHashMap(int initialCapacity, float loadFactor) {
        this(initialCapacity, loadFactor, 1);
    }

    /**
     * 创建一个新的，空的map，根据给定的元素数量计算出初始数组长度，并根据传入的负载因子以对应密度，
     * 以及同步级别对应的同步更新的线程数量初始化数组。
     *
     * @param initialCapacity 预计想要放入的节点数量，构造器内部会根据数量刚好不会扩容计算数组长度
     * @param loadFactor 为初始化数组长度提供的负载因子（数组密度）
     * @param concurrencyLevel 预估的同步更新的数量，可能会被用作容量指定的一个参考条件
     * @throws IllegalArgumentException 如果初始容量是负数，或负载因子为非正数，或同步级别为非正数
     */
    public ConcurrentHashMap(int initialCapacity,
                             float loadFactor, int concurrencyLevel) {
        if (!(loadFactor > 0.0f) || initialCapacity < 0 || concurrencyLevel <= 0)
            throw new IllegalArgumentException();
        if (initialCapacity < concurrencyLevel)   // 以满足给定的线程数为基准，使用尽量少的bin
            initialCapacity = concurrencyLevel;
        long size = (long)(1.0 + (long)initialCapacity / loadFactor);
        int cap = (size >= (long)MAXIMUM_CAPACITY) ?
            MAXIMUM_CAPACITY : tableSizeFor((int)size);
        this.sizeCtl = cap;
    }

    // 原始的 (since JDK1.2) Map 方法

    /**
     * {@inheritDoc}
     */
    public int size() {
        long n = sumCount();
        return ((n < 0L) ? 0 :
                (n > (long)Integer.MAX_VALUE) ? Integer.MAX_VALUE :
                (int)n);
    }

    /**
     * {@inheritDoc}
     */
    public boolean isEmpty() {
        return sumCount() <= 0L; // 忽视短暂的负值
    }

    /**
     * 返回给定的key对应的value，如果不存在该key则返回null。
     *
     * @throws NullPointerException 如果给定的key是null
     */
    public V get(Object key) {
        Node<K,V>[] tab; Node<K,V> e, p; int n, eh; K ek;
        int h = spread(key.hashCode()); // 通过扰动函数和下面的(n - 1) & h计算出下标，然后用tabAt获取对应下标的bin中的节点。
        if ((tab = table) != null && (n = tab.length) > 0 &&
            (e = tabAt(tab, (n - 1) & h)) != null) { // 如果从对应下标处获取到的节点e不为null
            if ((eh = e.hash) == h) { // 节点e的哈希值可以对上
                if ((ek = e.key) == key || (ek != null && key.equals(ek)))
                    return e.val; // 并且key对上，则返回对应的value
            }
            else if (eh < 0)
                return (p = e.find(h, key)) != null ? p.val : null;
            while ((e = e.next) != null) { // 不是上面两种情况，则沿着next指针寻找
                if (e.hash == h &&
                    ((ek = e.key) == key || (ek != null && key.equals(ek))))
                    return e.val;
            }
        }
        return null;
    }

    /**
     * 判断给定的key是否存在于当前map
     *
     * @param  key 给定的key
     * @return 当且仅当存在给定的key(通过equals判断)才返回true，否则返回false。
     * @throws NullPointerException 如果给定的key为null。
     */
    public boolean containsKey(Object key) {
        return get(key) != null;
    }

    /**
     * 如果map中有至少一个key的值等于给定的value，则返回true，否则返回false
     *
     * @param value 指定的value
     * @return 如果map中有至少一个key的值等于给定的value，则返回true，否则返回false
     * @throws NullPointerException 如果指定的value为null
     */
    public boolean containsValue(Object value) {
        if (value == null)
            throw new NullPointerException();
        Node<K,V>[] t;
        if ((t = table) != null) {
            Traverser<K,V> it = new Traverser<K,V>(t, t.length, 0, t.length);
            for (Node<K,V> p; (p = it.advance()) != null; ) {
                V v;
                if ((v = p.val) == value || (v != null && value.equals(v)))
                    return true;
            }
        }
        return false;
    }

    /**
     * 把给定的键值关系放入map中，如果本来就存在这个键，则会覆盖值。键和值都不能为null。
     *
     * @param key 指定的键
     * @param value 对应的值
     * @return 指定的键对应的原值，如果原来不存在则返回null
     * @throws NullPointerException 如果键或值为null
     */
    public V put(K key, V value) {
        return putVal(key, value, false);
    }

    /** put和putIfAbsent的实现 */
    final V putVal(K key, V value, boolean onlyIfAbsent) {
        if (key == null || value == null) throw new NullPointerException();
        int hash = spread(key.hashCode());
        int binCount = 0;
        for (Node<K,V>[] tab = table;;) {
            Node<K,V> f; int n, i, fh;
            if (tab == null || (n = tab.length) == 0)
                tab = initTable(); // 尝试初始化数组
            else if ((f = tabAt(tab, i = (n - 1) & hash)) == null) {
                if (casTabAt(tab, i, null,
                             new Node<K,V>(hash, key, value, null)))
                    break; // 往空的bin放入第一个元素时，使用CAS无锁尝试，如果放入成功，则直接结束，否则就是被其他线程抢先放入了，继续else
            }
            else if ((fh = f.hash) == MOVED) // 如果该bin的第一个元素是转移节点，说明正在进行扩容，要去帮忙转移。
                tab = helpTransfer(tab, f); // 转移完成后继续for循环，尝试往新的数组放这个节点。
            else { // 该bin中已经有节点了，把bin的首节点作为锁，锁住该bin。
                V oldVal = null;
                synchronized (f) {
                    if (tabAt(tab, i) == f) { // 锁住后确认首节点还在，如果不在了就直接进下一次循环再尝试插入
                        if (fh >= 0) { // 首节点是普通节点
                            binCount = 1;
                            for (Node<K,V> e = f;; ++binCount) {
                                K ek;
                                if (e.hash == hash &&
                                    ((ek = e.key) == key ||
                                     (ek != null && key.equals(ek)))) {
                                    oldVal = e.val;
                                    if (!onlyIfAbsent)
                                        e.val = value; // 如果key原来就存在，更新value后结束循环
                                    break;
                                }
                                Node<K,V> pred = e;
                                if ((e = e.next) == null) { // 走到链表末尾还没有找到key，则在链表尾部插入新节点并结束循环
                                    pred.next = new Node<K,V>(hash, key,
                                                              value, null);
                                    break;
                                }
                            }
                        }
                        else if (f instanceof TreeBin) { // 首节点是树节点
                            Node<K,V> p;
                            binCount = 2; // 非小于等于1的值，代表必须考虑扩容（具体见addCount方法）
                            if ((p = ((TreeBin<K,V>)f).putTreeVal(hash, key,
                                                           value)) != null) {
                                oldVal = p.val; // putTreeVal返回值不为null，则说明是原来存在的key。
                                if (!onlyIfAbsent)
                                    p.val = value;
                            }
                        }
                    }
                }
                if (binCount != 0) { // 插入新节点后检查是否需要转红黑树
                    if (binCount >= TREEIFY_THRESHOLD)
                        treeifyBin(tab, i); // 转红黑树
                    if (oldVal != null)
                        return oldVal; // 如果只是更新值，则直接返回旧值，不需要做后面的addCount
                    break;
                }
            }
        }
        addCount(1L, binCount); // 节点数加1
        return null;
    }

    /**
     * 将给定map中的所有映射关系拷贝到当前map中。
     * 如果两个map之间有相同的key，则当前map中该key对应的value会被覆盖
     *
     * @param m 指定的map
     */
    public void putAll(Map<? extends K, ? extends V> m) {
        tryPresize(m.size());
        for (Map.Entry<? extends K, ? extends V> e : m.entrySet())
            putVal(e.getKey(), e.getValue(), false);
    }

    /**
     * 如果存在的话，从map中删除指定的key的映射关系
     *
     * @param  key 指定的要删除的key
     * @return 返回在删除前该key对应的value，如果不存在这个key，都会返回null
     * @throws NullPointerException 如果指定的key是null
     */
    public V remove(Object key) {
        return replaceNode(key, null, null);
    }

    /**
     * 为4个public的remove/replace方法实现的一个通用方法：
     * 使用value覆盖对应key位置的值，如果value是null代表删除节点，
     * 如果cv不为null，则仅在原值等于cv的时候才执行操作。
     */
    final V replaceNode(Object key, V value, Object cv) {
        int hash = spread(key.hashCode());
        for (Node<K,V>[] tab = table;;) {
            Node<K,V> f; int n, i, fh;
            if (tab == null || (n = tab.length) == 0 ||
                (f = tabAt(tab, i = (n - 1) & hash)) == null)
                break;
            else if ((fh = f.hash) == MOVED) // 碰到转移节点，先帮忙扩容
                tab = helpTransfer(tab, f);
            else {
                V oldVal = null;
                boolean validated = false;
                synchronized (f) { // 锁住对应的bin
                    if (tabAt(tab, i) == f) {
                        if (fh >= 0) { // 如果首节点为非树节点，从首节点开始沿着next指针寻找
                            validated = true;
                            for (Node<K,V> e = f, pred = null;;) {
                                K ek;
                                if (e.hash == hash &&
                                    ((ek = e.key) == key ||
                                     (ek != null && key.equals(ek)))) {
                                    V ev = e.val;
                                    if (cv == null || cv == ev ||
                                        (ev != null && cv.equals(ev))) { // 找到了对应key的节点
                                        oldVal = ev;
                                        if (value != null) // value不为null，替代原值
                                            e.val = value;
                                        else if (pred != null) // value为null，根据pred判断删除的是首节点还是链表中间节点
                                            pred.next = e.next;
                                        else
                                            setTabAt(tab, i, e.next);
                                    }
                                    break;
                                }
                                pred = e;
                                if ((e = e.next) == null)
                                    break;
                            }
                        }
                        else if (f instanceof TreeBin) { // 如果首节点为树节点，从树的根节点开始寻找
                            validated = true;
                            TreeBin<K,V> t = (TreeBin<K,V>)f;
                            TreeNode<K,V> r, p;
                            if ((r = t.root) != null &&
                                (p = r.findTreeNode(hash, key, null)) != null) {
                                V pv = p.val;
                                if (cv == null || cv == pv ||
                                    (pv != null && cv.equals(pv))) {
                                    oldVal = pv;
                                    if (value != null) // value不为null，替代原值
                                        p.val = value;
                                    else if (t.removeTreeNode(p)) // value为null，删除节点
                                        setTabAt(tab, i, untreeify(t.first)); // 如果树太小则拆解树
                                }
                            }
                        }
                    }
                }
                if (validated) {
                    if (oldVal != null) {
                        if (value == null) // 原来有值但是更新后没有，则表示删除了节点，需要将节点数量减1，check=-1代表不考虑扩容
                            addCount(-1L, -1);
                        return oldVal;
                    }
                    break;
                }
            }
        }
        return null;
    }

    /**
     * 清除map中所有的映射关系
     */
    public void clear() {
        long delta = 0L; // 负数，表示删除节点的数量
        int i = 0;
        Node<K,V>[] tab = table;
        while (tab != null && i < tab.length) { // 遍历数组，删除节点
            int fh;
            Node<K,V> f = tabAt(tab, i);
            if (f == null)
                ++i;
            else if ((fh = f.hash) == MOVED) { // 碰到转发节点，代表正在进行扩容
                tab = helpTransfer(tab, f);
                i = 0; // 帮忙扩容后再从头开始清数组
            }
            else {
                synchronized (f) {
                    if (tabAt(tab, i) == f) {
                        Node<K,V> p = (fh >= 0 ? f :
                                       (f instanceof TreeBin) ?
                                       ((TreeBin<K,V>)f).first : null);
                        while (p != null) {
                            --delta;
                            p = p.next;
                        }
                        setTabAt(tab, i++, null);
                    }
                }
            }
        }
        if (delta != 0L)
            addCount(delta, -1);
    }

    /**
     * 以Set形式返回map中所有的key。这个集合是由map提供支持的，因此map的修改会反映到集合中，反之亦然。
     * 如果map在遍历正在进行时被修改，那么迭代的结果会有问题。
     * 该集合支持将元素从map中移除，也就是说如果我们通过Iterator.remove、Set.remove、removeAll、retainAll、clear方法操作这个集合，
     * 会导致map中同样key的映射关系也被移除。但它不支持add或allAll。
     *
     * 视图中的迭代器和分割迭代器都是弱一致的
     *
     * 其中分割迭代器Spliterator会具有Spliterator.CONCURRENT、Spliterator.DISTINCT、Spliterator.NONNULL属性
     *
     * @return key视图
     */
    public KeySetView<K,V> keySet() {
        KeySetView<K,V> ks;
        return (ks = keySet) != null ? ks : (keySet = new KeySetView<K,V>(this, null));
    }

    /**
     * 基本原理与keySet()相同
     *
     * 其中分割迭代器Spliterator会具有Spliterator.CONCURRENT、Spliterator.NONNULL属性
     *
     * @return value视图
     */
    public Collection<V> values() {
        ValuesView<K,V> vs;
        return (vs = values) != null ? vs : (values = new ValuesView<K,V>(this));
    }

    /**
     * 基本原理与keySet()相似。但是要注意它是支持放入新节点到map的。
     *
     * 其中分割迭代器Spliterator会具有Spliterator.CONCURRENT、Spliterator.DISTINCT、Spliterator.NONNULL属性
     *
     * @return 节点视图
     */
    public Set<Map.Entry<K,V>> entrySet() {
        EntrySetView<K,V> es;
        return (es = entrySet) != null ? es : (entrySet = new EntrySetView<K,V>(this));
    }

    /**
     * 返回当前map的哈希值，计算方法是将所有节点的键值的哈希值各自异或后相加
     *
     * @return 当前map的哈希值
     */
    public int hashCode() {
        int h = 0;
        Node<K,V>[] t;
        if ((t = table) != null) {
            Traverser<K,V> it = new Traverser<K,V>(t, t.length, 0, t.length);
            for (Node<K,V> p; (p = it.advance()) != null; )
                h += p.key.hashCode() ^ p.val.hashCode();
        }
        return h;
    }

    /**
     * 返回一个map的字符串表示。字符串由键值对（顺序不确定）拼接而成，两边用"{}"包括，每个元素之间用半角逗号和空格分开，键值之间用等号连接。
     *
     * @return 一个map的字符串表示
     */
    public String toString() {
        Node<K,V>[] t;
        int f = (t = table) == null ? 0 : t.length;
        Traverser<K,V> it = new Traverser<K,V>(t, f, 0, f);
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        Node<K,V> p;
        if ((p = it.advance()) != null) {
            for (;;) {
                K k = p.key;
                V v = p.val;
                sb.append(k == this ? "(this Map)" : k);
                sb.append('=');
                sb.append(v == this ? "(this Map)" : v);
                if ((p = it.advance()) == null)
                    break;
                sb.append(',').append(' ');
            }
        }
        return sb.append('}').toString();
    }

    /**
     * 比较给定的对象和当前map是否相等。如果两个map包含完全相同的映射关系，则返回true。如果另一个map被并发地修改，返回结果会不准确。
     *
     * @param o 要和当前map比较的对象
     * @return 如果map相等则返回true
     */
    public boolean equals(Object o) {
        if (o != this) {
            if (!(o instanceof Map))
                return false;
            Map<?,?> m = (Map<?,?>) o;
            Node<K,V>[] t;
            int f = (t = table) == null ? 0 : t.length;
            Traverser<K,V> it = new Traverser<K,V>(t, f, 0, f);
            // 先遍历当前map，每个key去给定map取value比较
            for (Node<K,V> p; (p = it.advance()) != null; ) {
                V val = p.val;
                Object v = m.get(p.key);
                if (v == null || (v != val && !v.equals(val)))
                    return false;
            }
            // 再遍历给定map，每个key取value和当前map比较
            for (Map.Entry<?,?> e : m.entrySet()) {
                Object mk, mv, v;
                if ((mk = e.getKey()) == null ||
                    (mv = e.getValue()) == null ||
                    (v = get(mk)) == null ||
                    (mv != v && !mv.equals(v)))
                    return false;
            }
        }
        return true;
    }

    /**
     * 上个版本使用的辅助类的无修饰的版本。为了序列化兼容而定义
     */
    static class Segment<K,V> extends ReentrantLock implements Serializable {
        private static final long serialVersionUID = 2249069246763182397L;
        final float loadFactor;
        Segment(float lf) { this.loadFactor = lf; }
    }

    /**
     * 将当前ConcurrentHashMap读到一个流中（即序列化）
     *
     * @param s 给定的流
     * @throws java.io.IOException 如果发生I/O异常
     * @serialData 序列化的内容如下：
     * 每个键值对的键和值的对象（顺序不确定），后面跟着一对null。
     */
    private void writeObject(java.io.ObjectOutputStream s)
        throws java.io.IOException {
        // 为了兼容本类的上一版本的序列化，假装进行segment计算
        int sshift = 0;
        int ssize = 1;
        while (ssize < DEFAULT_CONCURRENCY_LEVEL) {
            ++sshift;
            ssize <<= 1;
        }
        int segmentShift = 32 - sshift;
        int segmentMask = ssize - 1;
        @SuppressWarnings("unchecked")
        Segment<K,V>[] segments = (Segment<K,V>[])
            new Segment<?,?>[DEFAULT_CONCURRENCY_LEVEL];
        for (int i = 0; i < segments.length; ++i)
            segments[i] = new Segment<K,V>(LOAD_FACTOR);
        s.putFields().put("segments", segments);
        s.putFields().put("segmentShift", segmentShift);
        s.putFields().put("segmentMask", segmentMask);
        s.writeFields();

        Node<K,V>[] t;
        if ((t = table) != null) {
            Traverser<K,V> it = new Traverser<K,V>(t, t.length, 0, t.length);
            for (Node<K,V> p; (p = it.advance()) != null; ) {
                s.writeObject(p.key);
                s.writeObject(p.val);
            }
        }
        s.writeObject(null);
        s.writeObject(null);
        segments = null; // throw away
    }

    /**
     * 根据数据流重建map（即反序列化）
     * @param s 数据流
     * @throws ClassNotFoundException 如果序列化对象的类没找到
     * @throws java.io.IOException 发生I/O错误
     */
    private void readObject(java.io.ObjectInputStream s)
        throws java.io.IOException, ClassNotFoundException {
        /*
         * 为了在某些情况下提升性能，我们在读数据流的时候就创建节点，然后我们就知道了节点总数，然后再把刚才创建的节点放入数组中，不需要扩容。
         * 但是我们也必须验证唯一性，以及考虑插入值的方式（有可能是树结构）
         */
        sizeCtl = -1; // 数组初始化构造期间，排斥外部操作
        s.defaultReadObject();
        long size = 0L;
        Node<K,V> p = null;
        for (;;) {
            @SuppressWarnings("unchecked")
            K k = (K) s.readObject();
            @SuppressWarnings("unchecked")
            V v = (V) s.readObject();
            if (k != null && v != null) {
                p = new Node<K,V>(spread(k.hashCode()), k, v, p);
                ++size;
            }
            else
                break;
        }
        if (size == 0L)
            sizeCtl = 0;
        else {
            // 按照常规流程依次放入节点
            int n;
            if (size >= (long)(MAXIMUM_CAPACITY >>> 1))
                n = MAXIMUM_CAPACITY;
            else {
                int sz = (int)size;
                n = tableSizeFor(sz + (sz >>> 1) + 1);
            }
            @SuppressWarnings("unchecked")
            Node<K,V>[] tab = (Node<K,V>[])new Node<?,?>[n];
            int mask = n - 1;
            long added = 0L;
            while (p != null) {
                boolean insertAtFront;
                Node<K,V> next = p.next, first;
                int h = p.hash, j = h & mask;
                if ((first = tabAt(tab, j)) == null)
                    insertAtFront = true;
                else {
                    K k = p.key;
                    if (first.hash < 0) {
                        TreeBin<K,V> t = (TreeBin<K,V>)first;
                        if (t.putTreeVal(h, k, p.val) == null)
                            ++added;
                        insertAtFront = false;
                    }
                    else {
                        int binCount = 0;
                        insertAtFront = true;
                        Node<K,V> q; K qk;
                        for (q = first; q != null; q = q.next) {
                            if (q.hash == h &&
                                ((qk = q.key) == k ||
                                 (qk != null && k.equals(qk)))) {
                                insertAtFront = false;
                                break;
                            }
                            ++binCount;
                        }
                        if (insertAtFront && binCount >= TREEIFY_THRESHOLD) {
                            insertAtFront = false;
                            ++added;
                            p.next = first;
                            TreeNode<K,V> hd = null, tl = null;
                            for (q = p; q != null; q = q.next) {
                                TreeNode<K,V> t = new TreeNode<K,V>
                                    (q.hash, q.key, q.val, null, null);
                                if ((t.prev = tl) == null)
                                    hd = t;
                                else
                                    tl.next = t;
                                tl = t;
                            }
                            setTabAt(tab, j, new TreeBin<K,V>(hd));
                        }
                    }
                }
                if (insertAtFront) {
                    ++added;
                    p.next = first;
                    setTabAt(tab, j, p);
                }
                p = next;
            }
            table = tab;
            sizeCtl = n - (n >>> 2);
            baseCount = added;
        }
    }

    // ConcurrentMap方法

    /**
     * putVal的变种，如果map中已经存在这个key，则不改变值。返回值是这个key对应的原来的值。
     *
     * @return 如果map中已经存在这个key，返回值是这个key对应的原来的值。否则返回null
     * @throws NullPointerException 如果给定的value是null
     */
    public V putIfAbsent(K key, V value) {
        return putVal(key, value, true);
    }

    /**
     * 只有key和value都对上时才删除元素，如果有元素被删除则返回true。
     *
     * @throws NullPointerException 如果给定的key是null
     */
    public boolean remove(Object key, Object value) {
        if (key == null)
            throw new NullPointerException();
        return value != null && replaceNode(key, null, value) != null;
    }

    /**
     * 如果能找到key和value都相同的元素，则把旧的value替换为新的value。如果替换成功返回true。
     *
     * @throws NullPointerException 任何一个入参为null
     */
    public boolean replace(K key, V oldValue, V newValue) {
        if (key == null || oldValue == null || newValue == null)
            throw new NullPointerException();
        return replaceNode(key, newValue, oldValue) != null;
    }

    /**
     * 和put方法的不同点是：只有当key已经在map中存在时才更新值。返回旧的值。
     *
     * @return 如果map中已经存在这个key，返回值是这个key对应的原来的值。否则返回null
     * @throws NullPointerException 如果指定的key或value为null
     */
    public V replace(K key, V value) {
        if (key == null || value == null)
            throw new NullPointerException();
        return replaceNode(key, value, null);
    }

    // 重写JDK8+ Map扩展方法的默认实现

    /**
     * 返回给定的key对应的value，如果没有找到则返回给定的默认值。
     *
     * @throws NullPointerException 如果给定的key为null
     */
    public V getOrDefault(Object key, V defaultValue) {
        V v;
        return (v = get(key)) == null ? defaultValue : v;
    }

    public void forEach(BiConsumer<? super K, ? super V> action) {
        if (action == null) throw new NullPointerException();
        Node<K,V>[] t;
        if ((t = table) != null) {
            Traverser<K,V> it = new Traverser<K,V>(t, t.length, 0, t.length);
            for (Node<K,V> p; (p = it.advance()) != null; ) {
                action.accept(p.key, p.val);
            }
        }
    }

    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        if (function == null) throw new NullPointerException();
        Node<K,V>[] t;
        if ((t = table) != null) {
            Traverser<K,V> it = new Traverser<K,V>(t, t.length, 0, t.length);
            for (Node<K,V> p; (p = it.advance()) != null; ) {
                V oldValue = p.val;
                for (K key = p.key;;) {
                    V newValue = function.apply(key, oldValue);
                    // 注意这个空指针异常
                    if (newValue == null)
                        throw new NullPointerException();
                    if (replaceNode(key, newValue, oldValue) != null ||
                        (oldValue = get(key)) == null)
                        break;
                }
            }
        }
    }

    /**
     * 如果给定的key不存在于map中，则尝试以给定的方法计算出value值并将键值对放入map，如果计算结果为null则不放。
     * 这个计算方法的执行是具有原子性的，所以对每个key都只会尝试一次。计算过程中，任何对当前map的其他的更新操作都会被阻塞，
     * 所以计算过程一定要简短，并且禁止同时更新当前map中的其他键值对。
     *
     * @param key 给定的key
     * @param mappingFunction 用来计算value的方法
     * @return mappingFunction计算所得的结果
     * @throws NullPointerException 任何一个入参为null
     * @throws IllegalStateException 如果传入的方法被检测到在做一个不会结束的递归更新
     * @throws RuntimeException 发生计算方法内部未处理的异常
     */
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        if (key == null || mappingFunction == null)
            throw new NullPointerException();
        int h = spread(key.hashCode());
        V val = null;
        int binCount = 0;
        for (Node<K,V>[] tab = table;;) {
            Node<K,V> f; int n, i, fh;
            if (tab == null || (n = tab.length) == 0)
                tab = initTable(); // 如果数组为空，初始化数组
            else if ((f = tabAt(tab, i = (n - 1) & h)) == null) { // 对应bin为空
                Node<K,V> r = new ReservationNode<K,V>(); // 创建一个占位节点
                synchronized (r) {
                    if (casTabAt(tab, i, null, r)) { // CAS尝试将占位节点放入bin首位置
                        binCount = 1;
                        Node<K,V> node = null;
                        try {
                            if ((val = mappingFunction.apply(key)) != null)
                                node = new Node<K,V>(h, key, val, null);
                        } finally {
                            setTabAt(tab, i, node);
                        } // 若计算结果不为null则会将真正的节点顶替掉占位节点，否则将占位节点移除
                    }
                }
                if (binCount != 0)
                    break;
            }
            else if ((fh = f.hash) == MOVED) // 在对应bin找的是转移节点，尝试帮助扩容
                tab = helpTransfer(tab, f);
            else { // bin不为空，且不是转移节点
                boolean added = false;
                synchronized (f) {
                    if (tabAt(tab, i) == f) {
                        if (fh >= 0) { // 哈希值大于0，是普通节点，沿next指针寻找
                            binCount = 1;
                            for (Node<K,V> e = f;; ++binCount) {
                                K ek; V ev;
                                if (e.hash == h &&
                                    ((ek = e.key) == key ||
                                     (ek != null && key.equals(ek)))) {
                                    val = e.val;
                                    break;
                                }
                                Node<K,V> pred = e;
                                if ((e = e.next) == null) {
                                    if ((val = mappingFunction.apply(key)) != null) {
                                        added = true;
                                        pred.next = new Node<K,V>(h, key, val, null);
                                    }
                                    break;
                                }
                            }
                        }
                        else if (f instanceof TreeBin) { // bin首节点为树节点，用树扫描方式寻找
                            binCount = 2;
                            TreeBin<K,V> t = (TreeBin<K,V>)f;
                            TreeNode<K,V> r, p;
                            if ((r = t.root) != null &&
                                (p = r.findTreeNode(h, key, null)) != null)
                                val = p.val;
                            else if ((val = mappingFunction.apply(key)) != null) {
                                added = true;
                                t.putTreeVal(h, key, val);
                            }
                        }
                    }
                }
                if (binCount != 0) {
                    if (binCount >= TREEIFY_THRESHOLD)
                        treeifyBin(tab, i);
                    if (!added)
                        return val;
                    break;
                }
            }
        }
        if (val != null)
            addCount(1L, binCount);
        return val;
    }

    /**
     * 如果给定的key已存在于map，则尝试用给定的方法计算一个新的value覆盖旧值，如果计算结果为null则删除节点。
     * 这个计算方法的执行是具有原子性的，所以对每个key都只会尝试一次。计算过程中，任何对当前map的其他的更新操作都会被阻塞，
     * 所以计算过程一定要简短，并且禁止同时更新当前map中的其他键值对。
     *
     * @param key 给定的key
     * @param remappingFunction 用于计算value的方法
     * @return 返回remappingFunction的计算结果，如果key不存在则直接返回null
     * @throws NullPointerException 任何一个参数为null
     * @throws IllegalStateException 如果传入的方法被检测到在做一个不会结束的递归更新
     * @throws RuntimeException 发生计算方法内部未处理的异常
     */
    public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        if (key == null || remappingFunction == null)
            throw new NullPointerException();
        int h = spread(key.hashCode());
        V val = null;
        int delta = 0;
        int binCount = 0;
        for (Node<K,V>[] tab = table;;) {
            Node<K,V> f; int n, i, fh;
            if (tab == null || (n = tab.length) == 0)
                tab = initTable();
            else if ((f = tabAt(tab, i = (n - 1) & h)) == null)
                break;
            else if ((fh = f.hash) == MOVED)
                tab = helpTransfer(tab, f);
            else {
                synchronized (f) {
                    if (tabAt(tab, i) == f) {
                        if (fh >= 0) {
                            binCount = 1;
                            for (Node<K,V> e = f, pred = null;; ++binCount) {
                                K ek;
                                if (e.hash == h &&
                                    ((ek = e.key) == key ||
                                     (ek != null && key.equals(ek)))) {
                                    val = remappingFunction.apply(key, e.val);
                                    if (val != null)
                                        e.val = val;
                                    else { // 注意如果计算结果为null会移除该节点
                                        delta = -1;
                                        Node<K,V> en = e.next;
                                        if (pred != null)
                                            pred.next = en;
                                        else
                                            setTabAt(tab, i, en);
                                    }
                                    break;
                                }
                                pred = e;
                                if ((e = e.next) == null)
                                    break;
                            }
                        }
                        else if (f instanceof TreeBin) {
                            binCount = 2;
                            TreeBin<K,V> t = (TreeBin<K,V>)f;
                            TreeNode<K,V> r, p;
                            if ((r = t.root) != null &&
                                (p = r.findTreeNode(h, key, null)) != null) {
                                val = remappingFunction.apply(key, p.val);
                                if (val != null)
                                    p.val = val;
                                else { // 注意如果计算结果为null会移除该节点
                                    delta = -1;
                                    if (t.removeTreeNode(p))
                                        setTabAt(tab, i, untreeify(t.first));
                                }
                            }
                        }
                    }
                }
                if (binCount != 0)
                    break;
            }
        }
        if (delta != 0)
            addCount((long)delta, binCount);
        return val;
    }

    /**
     * 尝试以给定的key和其对应的value(如果不存在的话value用null代替)为入参执行remappingFunction方法得到新的value，替代旧value，
     * 如果计算结果为null则会删除原节点（如果存在的话）。这个计算方法的执行是具有原子性的，所以对每个key都只会尝试一次。
     * 计算过程中，任何对当前map的其他的更新操作都会被阻塞，所以计算过程一定要简短，并且禁止同时更新当前map中的其他键值对。
     *
     * @param key 给定的key
     * @param remappingFunction 用来计算value的方法
     * @return remappingFunction的计算结果
     * @throws NullPointerException 任何一个入参为null
     * @throws IllegalStateException 如果传入的方法被检测到在做一个不会结束的递归更新
     * @throws RuntimeException 发生计算方法内部未处理的异常
     */
    public V compute(K key,
                     BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        if (key == null || remappingFunction == null)
            throw new NullPointerException();
        int h = spread(key.hashCode());
        V val = null;
        int delta = 0;
        int binCount = 0;
        for (Node<K,V>[] tab = table;;) {
            Node<K,V> f; int n, i, fh;
            if (tab == null || (n = tab.length) == 0)
                tab = initTable();
            else if ((f = tabAt(tab, i = (n - 1) & h)) == null) {
                Node<K,V> r = new ReservationNode<K,V>();
                synchronized (r) {
                    if (casTabAt(tab, i, null, r)) {
                        binCount = 1;
                        Node<K,V> node = null;
                        try {
                            if ((val = remappingFunction.apply(key, null)) != null) {
                                delta = 1;
                                node = new Node<K,V>(h, key, val, null);
                            }
                        } finally {
                            setTabAt(tab, i, node);
                        }
                    }
                }
                if (binCount != 0)
                    break;
            }
            else if ((fh = f.hash) == MOVED)
                tab = helpTransfer(tab, f);
            else {
                synchronized (f) {
                    if (tabAt(tab, i) == f) {
                        if (fh >= 0) {
                            binCount = 1;
                            for (Node<K,V> e = f, pred = null;; ++binCount) {
                                K ek;
                                if (e.hash == h &&
                                    ((ek = e.key) == key ||
                                     (ek != null && key.equals(ek)))) {
                                    val = remappingFunction.apply(key, e.val);
                                    if (val != null)
                                        e.val = val;
                                    else {
                                        delta = -1;
                                        Node<K,V> en = e.next;
                                        if (pred != null)
                                            pred.next = en;
                                        else
                                            setTabAt(tab, i, en);
                                    }
                                    break;
                                }
                                pred = e;
                                if ((e = e.next) == null) {
                                    val = remappingFunction.apply(key, null);
                                    if (val != null) {
                                        delta = 1;
                                        pred.next =
                                            new Node<K,V>(h, key, val, null);
                                    }
                                    break;
                                }
                            }
                        }
                        else if (f instanceof TreeBin) {
                            binCount = 1;
                            TreeBin<K,V> t = (TreeBin<K,V>)f;
                            TreeNode<K,V> r, p;
                            if ((r = t.root) != null)
                                p = r.findTreeNode(h, key, null);
                            else
                                p = null;
                            V pv = (p == null) ? null : p.val;
                            val = remappingFunction.apply(key, pv);
                            if (val != null) {
                                if (p != null)
                                    p.val = val;
                                else {
                                    delta = 1;
                                    t.putTreeVal(h, key, val);
                                }
                            }
                            else if (p != null) {
                                delta = -1;
                                if (t.removeTreeNode(p))
                                    setTabAt(tab, i, untreeify(t.first));
                            }
                        }
                    }
                }
                if (binCount != 0) {
                    if (binCount >= TREEIFY_THRESHOLD)
                        treeifyBin(tab, i);
                    break;
                }
            }
        }
        if (delta != 0)
            addCount((long)delta, binCount);
        return val;
    }

    /**
     * 如果给定的key不存在于map中，则以给定的键值关系插入一个键值对，
     * 否则以旧value和传入的value为入参执行remappingFunction，如果结果不为null则把这个结果值作为新的value，如果结果为null则删除节点。
     * 这个计算方法的执行是具有原子性的，所以对每个key都只会尝试一次。
     * 计算过程中，任何对当前map的其他的更新操作都会被阻塞，所以计算过程一定要简短，并且禁止同时更新当前map中的其他键值对。
     *
     * @param key 给定的key
     * @param value 如果key不存在则直接指定的键值关系中的value
     * @param remappingFunction 给定的方法
     * @return 最终key对应的value，如果key被删了则返回true
     * @throws NullPointerException 任何一个入参为null
     * @throws RuntimeException 发生计算方法内部未处理的异常
     */
    public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        if (key == null || value == null || remappingFunction == null)
            throw new NullPointerException();
        int h = spread(key.hashCode());
        V val = null;
        int delta = 0;
        int binCount = 0;
        for (Node<K,V>[] tab = table;;) {
            Node<K,V> f; int n, i, fh;
            if (tab == null || (n = tab.length) == 0)
                tab = initTable();
            else if ((f = tabAt(tab, i = (n - 1) & h)) == null) {
                if (casTabAt(tab, i, null, new Node<K,V>(h, key, value, null))) {
                    delta = 1;
                    val = value;
                    break;
                }
            }
            else if ((fh = f.hash) == MOVED)
                tab = helpTransfer(tab, f);
            else {
                synchronized (f) {
                    if (tabAt(tab, i) == f) {
                        if (fh >= 0) {
                            binCount = 1;
                            for (Node<K,V> e = f, pred = null;; ++binCount) {
                                K ek;
                                if (e.hash == h &&
                                    ((ek = e.key) == key ||
                                     (ek != null && key.equals(ek)))) {
                                    val = remappingFunction.apply(e.val, value);
                                    if (val != null)
                                        e.val = val;
                                    else {
                                        delta = -1;
                                        Node<K,V> en = e.next;
                                        if (pred != null)
                                            pred.next = en;
                                        else
                                            setTabAt(tab, i, en);
                                    }
                                    break;
                                }
                                pred = e;
                                if ((e = e.next) == null) {
                                    delta = 1;
                                    val = value;
                                    pred.next =
                                        new Node<K,V>(h, key, val, null);
                                    break;
                                }
                            }
                        }
                        else if (f instanceof TreeBin) {
                            binCount = 2;
                            TreeBin<K,V> t = (TreeBin<K,V>)f;
                            TreeNode<K,V> r = t.root;
                            TreeNode<K,V> p = (r == null) ? null :
                                r.findTreeNode(h, key, null);
                            val = (p == null) ? value :
                                remappingFunction.apply(p.val, value);
                            if (val != null) {
                                if (p != null)
                                    p.val = val;
                                else {
                                    delta = 1;
                                    t.putTreeVal(h, key, val);
                                }
                            }
                            else if (p != null) {
                                delta = -1;
                                if (t.removeTreeNode(p))
                                    setTabAt(tab, i, untreeify(t.first));
                            }
                        }
                    }
                }
                if (binCount != 0) {
                    if (binCount >= TREEIFY_THRESHOLD)
                        treeifyBin(tab, i);
                    break;
                }
            }
        }
        if (delta != 0)
            addCount((long)delta, binCount);
        return val;
    }

    // Hashtable遗留的方法

    /**
     * 遗留的方法，判断map中有没有key对应给定的value。这方法和containsValue(Object)方法完全一致。
     * 为了确保它和Hashtable兼容所以把这个方法留下来了。
     *
     * @param  value 给定的value
     * @return     当且仅当有key对应的value和给定的value相同(equals)时才返回true，否则返回false
     * @exception  NullPointerException  如果给定的value是null
     */
    public boolean contains(Object value) {
        return containsValue(value);
    }

    /**
     * 返回一个key的枚举
     *
     * @return 一个key的枚举
     * @see #keySet()
     */
    public Enumeration<K> keys() {
        Node<K,V>[] t;
        int f = (t = table) == null ? 0 : t.length;
        return new KeyIterator<K,V>(t, f, 0, f, this);
    }

    /**
     * 返回一个value的枚举
     *
     * @return 一个value的枚举
     * @see #values()
     */
    public Enumeration<V> elements() {
        Node<K,V>[] t;
        int f = (t = table) == null ? 0 : t.length;
        return new ValueIterator<K,V>(t, f, 0, f, this);
    }

    // 仅ConcurrentHashMap使用的方法

    /**
     * 返回map中的映射数量，这个方法需要代替size方法，因为ConcurrentHashMap可能含有比size得到的int值更多的节点。
     * 这个数量只是一个估计，因为节点可能被并发地插入或移除。
     *
     * @return 键值映射对总数量
     * @since 1.8
     */
    public long mappingCount() {
        long n = sumCount();
        return (n < 0L) ? 0L : n; // 忽略短暂的负值
    }

    /**
     * 创建一个新的以当前key类型为key类型，布尔型为值类型的集合，默认值为Boolean.TRUE
     *
     * @param <K> 返回的集合中元素的类型
     * @return 新的集合
     * @since 1.8
     */
    public static <K> KeySetView<K,Boolean> newKeySet() {
        return new KeySetView<K,Boolean>
            (new ConcurrentHashMap<K,Boolean>(), Boolean.TRUE);
    }

    /**
     * 创建一个新的以当前key类型为key类型，布尔型为值类型的集合，默认值为Boolean.TRUE，并指定初始容量
     *
     * @param initialCapacity 初始容量
     * @param <K> 返回的集合中元素的类型
     * @return 新的集合
     * @throws IllegalArgumentException 如果给定的初始容量为负数
     * @since 1.8
     */
    public static <K> KeySetView<K,Boolean> newKeySet(int initialCapacity) {
        return new KeySetView<K,Boolean>
            (new ConcurrentHashMap<K,Boolean>(initialCapacity), Boolean.TRUE);
    }

    /**
     * 返回一个当前map的key的集合视图，并且指定默认映射值（这个值会影响KeySetView.add，KeySetView.addAll方法）。
     * 当然这只适合于可以用相同的默认值作为值的情景。
     *
     * @param mappedValue 附加的默认value
     * @return 集合视图
     * @throws NullPointerException 如果mappedValue为null
     */
    public KeySetView<K,V> keySet(V mappedValue) {
        if (mappedValue == null)
            throw new NullPointerException();
        return new KeySetView<K,V>(this, mappedValue);
    }

    /* ---------------- 特殊节点 -------------- */

    /**
     * 在转移操作进行时，放在bin开头的节点
     */
    static final class ForwardingNode<K,V> extends Node<K,V> {
        final Node<K,V>[] nextTable;
        ForwardingNode(Node<K,V>[] tab) {
            super(MOVED, null, null, null);
            this.nextTable = tab;
        }

        Node<K,V> find(int h, Object k) {
            // 循环以应对连续发生的扩容转发
            outer: for (Node<K,V>[] tab = nextTable;;) {
                Node<K,V> e; int n;
                if (k == null || tab == null || (n = tab.length) == 0 ||
                    (e = tabAt(tab, (n - 1) & h)) == null)
                    return null; // nextTable不存在或其对应位置没有节点，则返回null
                for (;;) { // nextTable对应位置有节点
                    int eh; K ek;
                    if ((eh = e.hash) == h &&
                        ((ek = e.key) == k || (ek != null && k.equals(ek))))
                        return e; // 如果首节点的哈希值和key都能对上，则返回这个节点
                    if (eh < 0) { // 否则判断首节点哈希值是否小于0，即是否又发生了扩容转发
                        if (e instanceof ForwardingNode) { // 如果的确是发生了扩容转发
                            tab = ((ForwardingNode<K,V>)e).nextTable; // 就更新tab继续外层循环
                            continue outer;
                        }
                        else // 如果没有再发生转发，则按照普通节点的方式沿着next检索
                            return e.find(h, k);
                    }
                    if ((e = e.next) == null)
                        return null;
                }
            }
        }
    }

    /**
     * computeIfAbsent和compute方法使用的占位节点
     */
    static final class ReservationNode<K,V> extends Node<K,V> {
        ReservationNode() {
            super(RESERVED, null, null, null);
        }

        Node<K,V> find(int h, Object k) {
            return null;
        }
    }

    /* ---------------- 数组初始化和扩容 -------------- */

    /**
     * 返回某次扩容的唯一标识戳。数组长度为n。
     * 返回值被RESIZE_STAMP_SHIFT左移16位后必须为负数。（因为它会被赋给sizeCtl，仅赋值能表示扩容进行中）
     *
     * 以长度16(二进制 10000)为例，一共32位，前面共32 - 5 = 27(二进制 11011)个0
     * 而1 << (RESIZE_STAMP_BITS - 1) = 1 << 15 = 0000 0000 0000 0000 1000 0000 0000 0000
     * 两者执行或操作结果为:      0000 0000 0000 0000 1000 0000 0001 1011
     * 验证其正确性，它左移16位为: 1000 0000 0001 1011 0000 0000 0000 0000
     * 的确是负数。往回去看，这与RESIZE_STAMP_SHIFT = 32 - RESIZE_STAMP_BITS这一关系是对应的
     */
    static final int resizeStamp(int n) {
        return Integer.numberOfLeadingZeros(n) | (1 << (RESIZE_STAMP_BITS - 1));
    }

    /**
     * 使用预存在sizeCtl中的值作为长度初始化数组
     */
    private final Node<K,V>[] initTable() {
        Node<K,V>[] tab; int sc;
        while ((tab = table) == null || tab.length == 0) {
            if ((sc = sizeCtl) < 0)
                Thread.yield(); // 已经有其他线程抢先初始化了，自旋等待即可
            else if (U.compareAndSwapInt(this, SIZECTL, sc, -1)) { // 将sizeCtl置为-1，使用Unsafe保证线程安全
                try {
                    if ((tab = table) == null || tab.length == 0) { // 再次确认数组未被初始化
                        int n = (sc > 0) ? sc : DEFAULT_CAPACITY;
                        @SuppressWarnings("unchecked")
                        Node<K,V>[] nt = (Node<K,V>[])new Node<?,?>[n];
                        table = tab = nt;
                        sc = n - (n >>> 2); // 将数组长度 * 0.75的值存入sizeCtl
                    }
                } finally {
                    sizeCtl = sc;
                }
                break;
            }
        }
        return tab;
    }

    /**
     * 将map的节点计数器加上x，如果需要扩容，且扩容未开始，则初始化扩容任务，否则根据情况帮助扩容。
     * 在一次扩容转移结束时会确认是否有下一次扩容。
     *
     * @param x 计数器需要加上的值
     * @param check 如果小于0（一般出现在删除节点后的调用），不确认扩容，如果小于等于1，则仅在无竞争时扩容。
     *              （无竞争：指counterCells没有初始化，且对baseCount的CAS操作没有发生冲突）
     */
    private final void addCount(long x, int check) {
        CounterCell[] as; long b, s;
        if ((as = counterCells) != null || // 如果counterCells已初始化，或counterCells未初始化且CAS增加数量失败，则进入if语句
            !U.compareAndSwapLong(this, BASECOUNT, b = baseCount, s = b + x)) {
            CounterCell a; long v; int m;
            boolean uncontended = true;
            // CounterCell数组未初始化，则进入fullAddCount
            // CounterCell数组长度小于1，则进入fullAddCount
            // 当前线程的probe(可以当成哈希值)与CounterCell数组长度与运算得到下标，如果该位置还没有计数器，则进入fullAddCount
            // 如果CounterCell数组对应位置已经有计数器，尝试将计数器+x，如果失败了则进入fullAddCount
            if (as == null || (m = as.length - 1) < 0 ||
                (a = as[ThreadLocalRandom.getProbe() & m]) == null ||
                !(uncontended =
                  U.compareAndSwapLong(a, CELLVALUE, v = a.value, v + x))) {
                fullAddCount(x, uncontended);
                return; // 一旦进了这个if，无论check为何值都不会去扩容了
            }
            if (check <= 1)
                return;
            s = sumCount(); // 将最新的节点总数给s
        }
        if (check >= 0) { // 考虑扩容，为方便理解，以当前table长度16(二进制 10000)为准进行扩容
            Node<K,V>[] tab, nt; int n, sc;
            while (s >= (long)(sc = sizeCtl) && (tab = table) != null &&
                   (n = tab.length) < MAXIMUM_CAPACITY) {
                int rs = resizeStamp(n); // rs = 0000 0000 0000 0000 1000 0000 0001 1011
                if (sc < 0) { // sizeCtl小于0，说明正在进行扩容
                    // (sc >>> RESIZE_STAMP_SHIFT) != rs，代表当前进行中的扩容已经不是长度n->2n的这一轮了，
                    // sc == rs + 1，扩容时sc为负，rs为正数且是一个不会溢出的值，所以条件不会成立
                    // sc == rs + MAX_RESIZERS，代表当前参加扩容的线程数已达上限
                    // (nt = nextTable) == null 和 transferIndex <= 0代表当前扩容任务已经完成了
                    if ((sc >>> RESIZE_STAMP_SHIFT) != rs || sc == rs + 1 ||
                        sc == rs + MAX_RESIZERS || (nt = nextTable) == null ||
                        transferIndex <= 0)
                        break;
                    if (U.compareAndSwapInt(this, SIZECTL, sc, sc + 1)) // 参加扩容，sizeCtl加1
                        transfer(tab, nt);
                }
                else if (U.compareAndSwapInt(this, SIZECTL, sc,
                                             (rs << RESIZE_STAMP_SHIFT) + 2))
                    // 开启扩容任务，将sizeCtl按当前扩容戳置为一个负数
                    // rs << RESIZE_STAMP_SHIFT + 2 = 1000 0000 0001 1011 0000 0000 0000 0010
                    transfer(tab, null);
                s = sumCount();
            }
        }
    }

    /**
     * 如果正在进行扩容，就需要帮忙转移。
     */
    final Node<K,V>[] helpTransfer(Node<K,V>[] tab, Node<K,V> f) {
        Node<K,V>[] nextTab; int sc;
        if (tab != null && (f instanceof ForwardingNode) &&
            (nextTab = ((ForwardingNode<K,V>)f).nextTable) != null) { // 确认扩容仍在进行
            int rs = resizeStamp(tab.length);
            while (nextTab == nextTable && table == tab &&
                   (sc = sizeCtl) < 0) { // 如果扩容正在进行，则不断循环
                // 判断扩容已结束的条件，可参考addCount方法中的注释
                if ((sc >>> RESIZE_STAMP_SHIFT) != rs || sc == rs + 1 ||
                    sc == rs + MAX_RESIZERS || transferIndex <= 0)
                    break;
                if (U.compareAndSwapInt(this, SIZECTL, sc, sc + 1)) { // 将sizeCtl加1
                    transfer(tab, nextTab); // 如果加成功了就可以去帮助转移了，否则继续下个while循环尝试。
                    break;
                }
            }
            return nextTab; // 返回扩容后的新数组
        }
        return table; // 调用方法时扩容已经结束了，返回新数组
    }

    /**
     * 尝试将数组容量调整到可以容纳指定数量的大小。
     *
     * @param size 元素数量（不需要非常准确）
     */
    private final void tryPresize(int size) {
        int c = (size >= (MAXIMUM_CAPACITY >>> 1)) ? MAXIMUM_CAPACITY :
            tableSizeFor(size + (size >>> 1) + 1); // c结果为指定数量的1.5倍+1（如果不超过最大限制），并向上取2的整数次幂的值
        int sc;
        while ((sc = sizeCtl) >= 0) { // 仅在其他线程没有进行初始化或扩容时才尝试初始化或扩容
            Node<K,V>[] tab = table; int n;
            if (tab == null || (n = tab.length) == 0) { // 如果数组未初始化
                n = (sc > c) ? sc : c; // 取sizeCtl和c之间的较大值作为初始容量
                if (U.compareAndSwapInt(this, SIZECTL, sc, -1)) { // 将sizeCtl置为-1，获取初始化锁
                    try {
                        if (table == tab) {
                            @SuppressWarnings("unchecked")
                            Node<K,V>[] nt = (Node<K,V>[])new Node<?,?>[n];
                            table = nt; // 初始化数组
                            sc = n - (n >>> 2); // 将sizeCtl置为初始容量的0.75倍
                        }
                    } finally {
                        sizeCtl = sc;
                    }
                }
            }
            else if (c <= sc || n >= MAXIMUM_CAPACITY) // 如果数组已初始化，且即使放入c个节点也不会扩容，或长度已到最大值，则直接返回
                break;
            else if (tab == table) { // 确认tab还是原来那个table
                int rs = resizeStamp(n);
                // 参与扩容的逻辑与addCount相同，可以参照addCount方法中相同的代码段注释。
                if (sc < 0) {
                    Node<K,V>[] nt;
                    if ((sc >>> RESIZE_STAMP_SHIFT) != rs || sc == rs + 1 ||
                        sc == rs + MAX_RESIZERS || (nt = nextTable) == null ||
                        transferIndex <= 0)
                        break;
                    if (U.compareAndSwapInt(this, SIZECTL, sc, sc + 1))
                        transfer(tab, nt);
                }
                else if (U.compareAndSwapInt(this, SIZECTL, sc,
                                             (rs << RESIZE_STAMP_SHIFT) + 2))
                    transfer(tab, null);
            }
        }
    }

    /**
     * 移动和/或拷贝当前数组的节点到新的数组中
     * 注解中假设当前任务是从256扩容到512，CPU数量为2
     * 梳理该方法前需要先理解，一次扩容后，原来下标为index的bin中的节点，要么留在原地，要么移动到(index + 原数组长度)处
     * 另外，注意只有多CPU的运行环境才会分子任务给多个线程处理转移
     */
    private final void transfer(Node<K,V>[] tab, Node<K,V>[] nextTab) {
        int n = tab.length, stride; // 如果是单CPU，直接把步长设为当前数组的长度n，则为n/8/CPU数。假设是256->512，n=256，stride=16
        if ((stride = (NCPU > 1) ? (n >>> 3) / NCPU : n) < MIN_TRANSFER_STRIDE)
            stride = MIN_TRANSFER_STRIDE; // 但有最小值限制16
        if (nextTab == null) {            // nextTab为空，表示刚要开启扩容任务
            try {
                @SuppressWarnings("unchecked")
                Node<K,V>[] nt = (Node<K,V>[])new Node<?,?>[n << 1];
                nextTab = nt;
            } catch (Throwable ex) {      // 处理内存溢出
                sizeCtl = Integer.MAX_VALUE;
                return;
            }
            nextTable = nextTab;
            transferIndex = n; // transferIndex = n = 256，注意这个transferIndex是全局的，记录了总任务的进度
        }
        int nextn = nextTab.length; // nextn = 512
        ForwardingNode<K,V> fwd = new ForwardingNode<K,V>(nextTab); // 创建一个转发节点
        boolean advance = true; // 每当完成一个bin的转发时，advance会置为true
        boolean finishing = false; // 用于扫描，保证所有线程都完成任务才会变成true
        for (int i = 0, bound = 0;;) {
            Node<K,V> f; int fh;
            while (advance) {
                int nextIndex, nextBound;
                if (--i >= bound || finishing) // 假如完成了第一个bin转移，--i = 254，目标bound = 240，可继续执行任务
                    advance = false;           // 假如--i已经是239了，说明已经完成了240~255的16个bin转移，则可以往前继续接任务
                else if ((nextIndex = transferIndex) <= 0) { // 首次进来nextIndex = 256
                    i = -1;
                    advance = false;
                }
                else if (U.compareAndSwapInt
                         (this, TRANSFERINDEX, nextIndex,
                          nextBound = (nextIndex > stride ?
                                       nextIndex - stride : 0))) { // nextBound = 256 - 16 = 240
                    bound = nextBound; // bound = 240              // 如果非首次/或帮忙的线程进来，则看情况往前取16个
                    i = nextIndex - 1; // i = 255
                    advance = false;
                }
            }
            if (i < 0 || i >= n || i + n >= nextn) { // i < 0 则整个任务已经完成
                int sc;
                if (finishing) {
                    nextTable = null;
                    table = nextTab; // 完成数组交接
                    sizeCtl = (n << 1) - (n >>> 1); // sizeCtl计算结果256*1.5=384，刚好是新容量512的0.75倍
                    return;
                }
                if (U.compareAndSwapInt(this, SIZECTL, sc = sizeCtl, sc - 1)) { // 将参与任务的线程数减1
                    // 这里与首个线程开启任务时U.compareAndSwapInt(this, SIZECTL, sc, (rs << RESIZE_STAMP_SHIFT) + 2))照应
                    // 仅当(sc - 2) == resizeStamp(n) << RESIZE_STAMP_SHIFT条件满足时，说明已经时最后一个线程，此时才能标记任务完成
                    if ((sc - 2) != resizeStamp(n) << RESIZE_STAMP_SHIFT)
                        return;
                    finishing = advance = true; // 标记任务已完成
                    i = n; // 提交之前再次确认
                }
            }
            else if ((f = tabAt(tab, i)) == null) // 如果原数组对应位置为空
                advance = casTabAt(tab, i, null, fwd); // 则在该位置放入刚才创建的转发节点
            else if ((fh = f.hash) == MOVED)
                advance = true; // 已经原数组对应位置被放入一个转发节点了
            else { // 如果原数组对应位置不为空，则需要进行转移
                synchronized (f) { // 锁住该bin的首节点
                    if (tabAt(tab, i) == f) {
                        Node<K,V> ln, hn;
                        if (fh >= 0) { // 首节点哈希值大于0，非树节点
                            /*
                             看过HashMap的扩容转移的话应该知道，一个bin中有一部分留在原位置，另一部分往后移动原数组长度距离。
                             这里用的lastRun只是为了尽可能复用原链表，举个例子，以0表示留在原地，1表示要往后移动：
                             A -> B -> C -> D -> E -> F -> G
                             0    0    1    1    0    0    0
                             从A开始，每当next的0/1发生变化，lastRun会被更新为那个next节点。这里最终lastRun为E节点。
                             ln会被指向E节点，即E->F->G链表得到了复用
                             */
                            int runBit = fh & n;
                            Node<K,V> lastRun = f;
                            for (Node<K,V> p = f.next; p != null; p = p.next) {
                                int b = p.hash & n;
                                if (b != runBit) {
                                    runBit = b;
                                    lastRun = p;
                                }
                            }
                            if (runBit == 0) {
                                ln = lastRun;
                                hn = null;
                            }
                            else {
                                hn = lastRun;
                                ln = null;
                            }
                            /*
                            这里会把两个链表各自拼接，接着刚才的例子，结果为：
                            原地不动组: B -> A -> E -> E -> F -> G
                            向后移动组: D -> C
                            注意next顺序较原来有变化
                             */
                            for (Node<K,V> p = f; p != lastRun; p = p.next) {
                                int ph = p.hash; K pk = p.key; V pv = p.val;
                                if ((ph & n) == 0)
                                    ln = new Node<K,V>(ph, pk, pv, ln);
                                else
                                    hn = new Node<K,V>(ph, pk, pv, hn);
                            }
                            setTabAt(nextTab, i, ln);
                            setTabAt(nextTab, i + n, hn);
                            setTabAt(tab, i, fwd); // 给原数组这个位置放入转发节点表示转发完成了
                            advance = true;
                        }
                        else if (f instanceof TreeBin) { // 首节点哈希值小于0，是树节点
                            // 树的转移思路和HashMap一样，有兴趣的读者可以看下HashMap.TreeNode<K,V>的方法split
                            TreeBin<K,V> t = (TreeBin<K,V>)f;
                            TreeNode<K,V> lo = null, loTail = null;
                            TreeNode<K,V> hi = null, hiTail = null;
                            int lc = 0, hc = 0;
                            for (Node<K,V> e = t.first; e != null; e = e.next) {
                                int h = e.hash;
                                TreeNode<K,V> p = new TreeNode<K,V>
                                    (h, e.key, e.val, null, null);
                                if ((h & n) == 0) {
                                    if ((p.prev = loTail) == null)
                                        lo = p;
                                    else
                                        loTail.next = p;
                                    loTail = p;
                                    ++lc;
                                }
                                else {
                                    if ((p.prev = hiTail) == null)
                                        hi = p;
                                    else
                                        hiTail.next = p;
                                    hiTail = p;
                                    ++hc;
                                }
                            }
                            ln = (lc <= UNTREEIFY_THRESHOLD) ? untreeify(lo) :
                                (hc != 0) ? new TreeBin<K,V>(lo) : t;
                            hn = (hc <= UNTREEIFY_THRESHOLD) ? untreeify(hi) :
                                (lc != 0) ? new TreeBin<K,V>(hi) : t;
                            setTabAt(nextTab, i, ln);
                            setTabAt(nextTab, i + n, hn);
                            setTabAt(tab, i, fwd);
                            advance = true;
                        }
                    }
                }
            }
        }
    }

    /* ---------------- 计数支持 -------------- */

    /**
     * 一个填充单元格，为分散的计数提供支持。利用LongAdder和Striped64实现，具体细节可以查看它们的文档。
     *
     * 使用@sun.misc.Contended注解代表该类会占用CPU单独的缓存行，例如在双核处理器中，它会保存到A、B两个CPU的高速缓存，
     * 如果A中修改了它，A会同时将B中的缓存失效掉，让B重新从主存中获取新的值。该注解适用于线程间共享的，高访问和修改频率的字段或类。
     */
    @sun.misc.Contended static final class CounterCell {
        volatile long value;
        CounterCell(long x) { value = x; }
    }

    /**
     * 统计baseCount计数器与CounterCell数组计数器的计数总和
     */
    final long sumCount() {
        CounterCell[] as = counterCells; CounterCell a;
        long sum = baseCount;
        if (as != null) {
            for (int i = 0; i < as.length; ++i) {
                if ((a = as[i]) != null)
                    sum += a.value;
            }
        }
        return sum;
    }

    /**
     * 该方法必会将x值加到计数器数组或baseCount中去
     * @param x 加上的值
     * @param wasUncontended 如果已尝试过往cell数组对应位置增加值但失败了，则传入false，否则传入true
     */
    private final void fullAddCount(long x, boolean wasUncontended) {
        int h;
        if ((h = ThreadLocalRandom.getProbe()) == 0) {
            ThreadLocalRandom.localInit(); // localInit()执行前getProbe()为0，执行后getProbe()的值必不为0
            h = ThreadLocalRandom.getProbe();
            wasUncontended = true; // 刚才尝试时probe是0，但现在初始化了一个值，相当于把h = ThreadLocalRandom.advanceProbe(h)
        }                          // 这一句rehash提前，所以wasUncontended也提前置为true，少一次无用的循环
        boolean collide = false;                // 如果上一次循环cell数组定位发生了冲突，则置该值为true
        for (;;) {
            CounterCell[] as; CounterCell a; int n; long v;
            if ((as = counterCells) != null && (n = as.length) > 0) { // 如果counterCells已经初始化了
                if ((a = as[(n - 1) & h]) == null) { // 根据counterCells的长度和当前线程的probe拿到下标，如果对应位置计数器为空
                    if (cellsBusy == 0) {            // 尝试给这个位置创建一个计数器
                        CounterCell r = new CounterCell(x);
                        if (cellsBusy == 0 &&
                            U.compareAndSwapInt(this, CELLSBUSY, 0, 1)) { // 尝试获取counterCells锁
                            boolean created = false;
                            try {               // 获取锁之后再次确认
                                CounterCell[] rs; int m, j;
                                if ((rs = counterCells) != null &&
                                    (m = rs.length) > 0 &&
                                    rs[j = (m - 1) & h] == null) {
                                    rs[j] = r;
                                    created = true;
                                }
                            } finally {
                                cellsBusy = 0; // 释放锁
                            }
                            if (created) // 此次创建cell成功了，则结束循环
                                break;
                            continue;           // 已经被其他线程抢先，则继续自旋
                        }
                    }
                    collide = false;
                }
                else if (!wasUncontended)       // 已尝试过CAS但失败了
                    wasUncontended = true;      // rehash后继续循环
                else if (U.compareAndSwapLong(a, CELLVALUE, v = a.value, v + x))
                    break; // CAS尝试给对应位置已初始化的计数器加x
                else if (counterCells != as || n >= NCPU)
                    collide = false;            // cell数组已被其他线程扩容，或其长度已经超过了CPU数量，则不再扩容。
                else if (!collide)
                    collide = true;
                else if (cellsBusy == 0 &&
                         U.compareAndSwapInt(this, CELLSBUSY, 0, 1)) { // 尝试获取counterCells锁
                    try {
                        if (counterCells == as) { // 获取锁后确认数组是最新的，然后扩容为原来的2倍，并把原来的数据复制到对应下标
                            CounterCell[] rs = new CounterCell[n << 1];
                            for (int i = 0; i < n; ++i)
                                rs[i] = as[i];
                            counterCells = rs;
                        }
                    } finally {
                        cellsBusy = 0;
                    }
                    collide = false;
                    continue;                   // 使用扩容后的cell数组重试
                }
                h = ThreadLocalRandom.advanceProbe(h); // rehash
            }
            else if (cellsBusy == 0 && counterCells == as &&
                     U.compareAndSwapInt(this, CELLSBUSY, 0, 1)) { // 如果counterCells未初始化，尝试获取锁，并初始化
                boolean init = false;
                try {                           // 初始化
                    if (counterCells == as) {
                        CounterCell[] rs = new CounterCell[2];
                        rs[h & 1] = new CounterCell(x);
                        counterCells = rs;
                        init = true;
                    }
                } finally {
                    cellsBusy = 0; // 释放初始化锁
                }
                if (init)
                    break;
            }
            else if (U.compareAndSwapLong(this, BASECOUNT, v = baseCount, v + x))
                break;                          // 再尝试一下改变baseCount的值
        }
    }

    /* ---------------- 转到红黑树及从红黑树转回 -------------- */

    /**
     * 把给定位置的链表转为红黑树，但如果目前table容量过小（默认为64）则优先考虑扩容。
     */
    private final void treeifyBin(Node<K,V>[] tab, int index) {
        Node<K,V> b; int n, sc;
        if (tab != null) {
            if ((n = tab.length) < MIN_TREEIFY_CAPACITY)
                tryPresize(n << 1); // 扩容
            else if ((b = tabAt(tab, index)) != null && b.hash >= 0) { // 确认bin的首节点是普通节点
                synchronized (b) { // 锁住首节点
                    if (tabAt(tab, index) == b) {
                        TreeNode<K,V> hd = null, tl = null;
                        for (Node<K,V> e = b; e != null; e = e.next) { // 从首节点开始沿着next指针将节点依次转为TreeNode
                            TreeNode<K,V> p =
                                new TreeNode<K,V>(e.hash, e.key, e.val,
                                                  null, null);
                            if ((p.prev = tl) == null)
                                hd = p;
                            else
                                tl.next = p;
                            tl = p; // 注意这个循环中仅维护了节点之间的next和prev指针
                        }
                        setTabAt(tab, index, new TreeBin<K,V>(hd)); // 传入链表头节点建立红黑树并放入数组
                    }
                }
            }
        }
    }

    /**
     * 拆解红黑树并返回链表头
     */
    static <K,V> Node<K,V> untreeify(Node<K,V> b) {
        Node<K,V> hd = null, tl = null;
        for (Node<K,V> q = b; q != null; q = q.next) {
            Node<K,V> p = new Node<K,V>(q.hash, q.key, q.val, null);
            if (tl == null)
                hd = p;
            else
                tl.next = p;
            tl = p;
        }
        return hd;
    }

    /* ---------------- 树节点 -------------- */

    /**
     * TreeBin中使用的树节点
     */
    static final class TreeNode<K,V> extends Node<K,V> {
        TreeNode<K,V> parent;  // 父节点
        TreeNode<K,V> left;
        TreeNode<K,V> right;
        TreeNode<K,V> prev;    // 用于节点删除时能够找到它的前继元素，如果没有这个指针，它的后继元素会找不到对接人。
        boolean red;

        TreeNode(int hash, K key, V val, Node<K,V> next,
                 TreeNode<K,V> parent) {
            super(hash, key, val, next);
            this.parent = parent;
        }

        Node<K,V> find(int h, Object k) {
            return findTreeNode(h, k, null);
        }

        /**
         * 从当前节点开始查找，返回给定的key对应的TreeNode，如果没找到返回null。
         * 详细注解可查看HashMap对应方法，此处略。
         */
        final TreeNode<K,V> findTreeNode(int h, Object k, Class<?> kc) {
            if (k != null) {
                TreeNode<K,V> p = this;
                do  {
                    int ph, dir; K pk; TreeNode<K,V> q;
                    TreeNode<K,V> pl = p.left, pr = p.right;
                    if ((ph = p.hash) > h)
                        p = pl;
                    else if (ph < h)
                        p = pr;
                    else if ((pk = p.key) == k || (pk != null && k.equals(pk)))
                        return p;
                    else if (pl == null)
                        p = pr;
                    else if (pr == null)
                        p = pl;
                    else if ((kc != null ||
                              (kc = comparableClassFor(k)) != null) &&
                             (dir = compareComparables(kc, k, pk)) != 0)
                        p = (dir < 0) ? pl : pr;
                    else if ((q = pr.findTreeNode(h, k, kc)) != null)
                        return q;
                    else
                        p = pl;
                } while (p != null);
            }
            return null;
        }
    }

    /* ---------------- TreeBins -------------- */

    /**
     * 树结构bin的头部，作为节点放在数组对应位置，TreeBin不包含任何键或值，它指向一组TreeNode和它们的根节点。
     * 同时TreeBin维护了一个读写锁，强制使写操作要等到读操作结束才能更新树结构。
     */
    static final class TreeBin<K,V> extends Node<K,V> {
        TreeNode<K,V> root;
        volatile TreeNode<K,V> first;
        volatile Thread waiter;
        volatile int lockState;
        // 锁状态的值
        static final int WRITER = 1; // 写锁(二进制 1)
        static final int WAITER = 2; // 等待锁(二进制 10)
        static final int READER = 4; // 读锁要增加的值(二进制 100)

        /**
         * 如果新插入的key和当前比较的节点哈希值相等，且没有实现Comparable接口，导致无法比较，但是既然是树结构，总是要分出谁大谁小的，
         * 这个方法就是最终手段，先把类名基于String.compareTo()比较，如果比出了大小就返回结果，如果还是一样，就由系统生成一个哈希值，
         * 可以看到比较这对哈希值时是用了<=号的，这是最后的保险，即使这个哈希值相等也会判定一方胜出。
         */
        static int tieBreakOrder(Object a, Object b) {
            int d;
            if (a == null || b == null ||
                (d = a.getClass().getName().
                 compareTo(b.getClass().getName())) == 0)
                d = (System.identityHashCode(a) <= System.identityHashCode(b) ?
                     -1 : 1);
            return d;
        }

        /**
         * 用给定的链表头b创建一个红黑树bin
         */
        TreeBin(TreeNode<K,V> b) {
            super(TREEBIN, null, null, null); // 红黑树的根，hash设为特殊的TREEBIN = -2
            this.first = b;
            TreeNode<K,V> r = null;
            for (TreeNode<K,V> x = b, next; x != null; x = next) {
                next = (TreeNode<K,V>)x.next;
                x.left = x.right = null;
                if (r == null) {
                    x.parent = null;
                    x.red = false;
                    r = x;
                }
                else {
                    K k = x.key;
                    int h = x.hash;
                    Class<?> kc = null;
                    for (TreeNode<K,V> p = r;;) {
                        int dir, ph;
                        K pk = p.key;
                        if ((ph = p.hash) > h)
                            dir = -1;
                        else if (ph < h)
                            dir = 1;
                        else if ((kc == null &&
                                  (kc = comparableClassFor(k)) == null) ||
                                 (dir = compareComparables(kc, k, pk)) == 0)
                            dir = tieBreakOrder(k, pk);
                            TreeNode<K,V> xp = p;
                        if ((p = (dir <= 0) ? p.left : p.right) == null) {
                            x.parent = xp;
                            if (dir <= 0)
                                xp.left = x;
                            else
                                xp.right = x;
                            r = balanceInsertion(r, x);
                            break;
                        }
                    }
                }
            }
            this.root = r;
            assert checkInvariants(root);
        }

        /**
         * 获取调整树结构的写锁
         */
        private final void lockRoot() {
            if (!U.compareAndSwapInt(this, LOCKSTATE, 0, WRITER))
                contendedLock(); // 自旋反复尝试获取锁
        }

        /**
         * 释放锁
         */
        private final void unlockRoot() {
            lockState = 0; // 直接把所有锁释放掉，不怕误操作释放掉别人的读锁和等待锁吗？
                           // 不会。其一，当一个线程持有写锁时，读操作会直接走链表而不会获取读锁之后再读；
                           // 其二，当一个线程持有写锁时，对于第二个想要写的线程，首先外层的同步代码段就进不来，更不会到这里来竞争写锁
        }

        /**
         * 可能阻塞，以等待锁释放
         */
        private final void contendedLock() {
            boolean waiting = false;
            for (int s;;) {
                if (((s = lockState) & ~WAITER) == 0) { // ~WAITER = ...1111101，当读、写锁都空闲时条件成立
                    if (U.compareAndSwapInt(this, LOCKSTATE, s, WRITER)) { // 尝试获取写锁
                        if (waiting)
                            waiter = null; // 如果上一次循环把自己设为了等待线程，将其置空（因为已经成功获取锁了）
                        return;
                    }
                }
                else if ((s & WAITER) == 0) { // WAITER = ...0000010，当等待锁空闲时条件成立
                    if (U.compareAndSwapInt(this, LOCKSTATE, s, s | WAITER)) { // 尝试获取等待锁
                        waiting = true;
                        waiter = Thread.currentThread(); // 把当前线程标记为等待线程，继续自旋，如果下次循环拿不到锁，会把自己挂起
                    }
                }
                else if (waiting)
                    LockSupport.park(this); // 暂时挂起线程等待许可，unpark只在读方法中出现，因为写操作的同步已经在外部处理掉
                                                    // 了，不需要在释放写锁的时候去unpark。
            }
        }

        /**
         * 返回对应的节点，如果不存在则返回null。如果能获取到读锁，则以树扫描规则搜寻节点，
         * 如果获取不到读锁，则以链表方式搜寻节点。
         */
        final Node<K,V> find(int h, Object k) {
            if (k != null) {
                for (Node<K,V> e = first; e != null; ) {
                    int s; K ek;
                    if (((s = lockState) & (WAITER|WRITER)) != 0) { // 如果写锁和等待锁至少有一个被持有，就以next指针遍历
                        if (e.hash == h &&
                            ((ek = e.key) == k || (ek != null && k.equals(ek))))
                            return e;
                        e = e.next;
                    }
                    else if (U.compareAndSwapInt(this, LOCKSTATE, s,
                                                 s + READER)) { // 如果写锁和等待锁均未被持有，则获取读锁，并以树扫描规则遍历
                        TreeNode<K,V> r, p;
                        try {
                            p = ((r = root) == null ? null :
                                 r.findTreeNode(h, k, null));
                        } finally {
                            Thread w;
                            if (U.getAndAddInt(this, LOCKSTATE, -READER) == // 释放掉读锁，并且如果此时锁空闲，释放挂起的线程
                                (READER|WAITER) && (w = waiter) != null)
                                LockSupport.unpark(w);
                        }
                        return p;
                    }
                }
            }
            return null;
        }

        /**
         * 查找并添加一个节点到树
         * @return 如果已添加过则返回null
         */
        final TreeNode<K,V> putTreeVal(int h, K k, V v) {
            Class<?> kc = null;
            boolean searched = false;
            for (TreeNode<K,V> p = root;;) {
                int dir, ph; K pk;
                if (p == null) { // 根节点为空，直接创建树节点作为根节点和首节点
                    first = root = new TreeNode<K,V>(h, k, v, null, null);
                    break;
                } // 根节点不为空，从根节点开始扫描红黑树，扫描方式和HashMap红黑树相同。
                else if ((ph = p.hash) > h)
                    dir = -1;
                else if (ph < h)
                    dir = 1;
                else if ((pk = p.key) == k || (pk != null && k.equals(pk)))
                    return p;
                else if ((kc == null &&
                          (kc = comparableClassFor(k)) == null) ||
                         (dir = compareComparables(kc, k, pk)) == 0) {
                    if (!searched) {
                        TreeNode<K,V> q, ch;
                        searched = true;
                        if (((ch = p.left) != null &&
                             (q = ch.findTreeNode(h, k, kc)) != null) ||
                            ((ch = p.right) != null &&
                             (q = ch.findTreeNode(h, k, kc)) != null))
                            return q;
                    }
                    dir = tieBreakOrder(k, pk);
                }

                TreeNode<K,V> xp = p;
                if ((p = (dir <= 0) ? p.left : p.right) == null) { // 找到了空的叶子节点
                    TreeNode<K,V> x, f = first; // 将当前首节点暂存到f
                    first = x = new TreeNode<K,V>(h, k, v, f, xp); // 创建一个新节点，同时指定为首节点，新节点的next指向原首节点
                    if (f != null)
                        f.prev = x; // 原首节点的prev指针指向新节点
                    if (dir <= 0)
                        xp.left = x;
                    else
                        xp.right = x;
                    if (!xp.red) // 父节点是黑色节点
                        x.red = true; // 因为新节点是红色节点，不需要旋转
                    else { // 如果父节点是红色，则需要调整平衡
                        lockRoot(); // 锁住根节点
                        try {
                            root = balanceInsertion(root, x);
                        } finally {
                            unlockRoot(); // 旋转后解锁根节点
                        }
                    }
                    break;
                }
            }
            assert checkInvariants(root); // 断言确认红黑树结构的规范性
            return null;
        }

        /**
         * 删除当前节点，这个方法比典型的红黑树元素移除代码要复杂。因为通过next指针是找不到"继承者"节点的，所以这里要用树规则扫描。
         *
         * @return 如果树太小，需要拆解，则返回true
         */
        final boolean removeTreeNode(TreeNode<K,V> p) {
            TreeNode<K,V> next = (TreeNode<K,V>)p.next;
            TreeNode<K,V> pred = p.prev;  // 拆解遍历指针
            TreeNode<K,V> r, rl;
            if (pred == null) // 没有前置节点，说明要删除的是首节点，只要把next节点前移到首节点位置即可移除这个节点
                first = next;
            else
                pred.next = next;
            if (next != null)
                next.prev = pred;
            if (first == null) { // 移除的是树中最后一个元节点
                root = null;
                return true;
            }
            if ((r = root) == null || r.right == null || // 树太小，需要拆解
                (rl = r.left) == null || rl.left == null)
                return true;
            lockRoot();
            try { // 以下代码段和HashMap相似，如有需要请参阅HashMap的removeTreeNode方法的注释
                TreeNode<K,V> replacement;
                TreeNode<K,V> pl = p.left;
                TreeNode<K,V> pr = p.right;
                if (pl != null && pr != null) {
                    TreeNode<K,V> s = pr, sl;
                    while ((sl = s.left) != null) // find successor
                        s = sl;
                    boolean c = s.red; s.red = p.red; p.red = c; // swap colors
                    TreeNode<K,V> sr = s.right;
                    TreeNode<K,V> pp = p.parent;
                    if (s == pr) { // p was s's direct parent
                        p.parent = s;
                        s.right = p;
                    }
                    else {
                        TreeNode<K,V> sp = s.parent;
                        if ((p.parent = sp) != null) {
                            if (s == sp.left)
                                sp.left = p;
                            else
                                sp.right = p;
                        }
                        if ((s.right = pr) != null)
                            pr.parent = s;
                    }
                    p.left = null;
                    if ((p.right = sr) != null)
                        sr.parent = p;
                    if ((s.left = pl) != null)
                        pl.parent = s;
                    if ((s.parent = pp) == null)
                        r = s;
                    else if (p == pp.left)
                        pp.left = s;
                    else
                        pp.right = s;
                    if (sr != null)
                        replacement = sr;
                    else
                        replacement = p;
                }
                else if (pl != null)
                    replacement = pl;
                else if (pr != null)
                    replacement = pr;
                else
                    replacement = p;
                if (replacement != p) {
                    TreeNode<K,V> pp = replacement.parent = p.parent;
                    if (pp == null)
                        r = replacement;
                    else if (p == pp.left)
                        pp.left = replacement;
                    else
                        pp.right = replacement;
                    p.left = p.right = p.parent = null;
                }

                root = (p.red) ? r : balanceDeletion(r, replacement);

                if (p == replacement) {  // detach pointers
                    TreeNode<K,V> pp;
                    if ((pp = p.parent) != null) {
                        if (p == pp.left)
                            pp.left = null;
                        else if (p == pp.right)
                            pp.right = null;
                        p.parent = null;
                    }
                }
            } finally {
                unlockRoot();
            }
            assert checkInvariants(root);
            return false;
        }

        /* ------------------------------------------------------------ */
        // 红黑树方法，均改编自CLR(Common Language Runtime)
        // 详细注释在本仓库HashMap源码中有，此处省略

        static <K,V> TreeNode<K,V> rotateLeft(TreeNode<K,V> root,
                                              TreeNode<K,V> p) {
            TreeNode<K,V> r, pp, rl;
            if (p != null && (r = p.right) != null) {
                if ((rl = p.right = r.left) != null)
                    rl.parent = p;
                if ((pp = r.parent = p.parent) == null)
                    (root = r).red = false;
                else if (pp.left == p)
                    pp.left = r;
                else
                    pp.right = r;
                r.left = p;
                p.parent = r;
            }
            return root;
        }

        static <K,V> TreeNode<K,V> rotateRight(TreeNode<K,V> root,
                                               TreeNode<K,V> p) {
            TreeNode<K,V> l, pp, lr;
            if (p != null && (l = p.left) != null) {
                if ((lr = p.left = l.right) != null)
                    lr.parent = p;
                if ((pp = l.parent = p.parent) == null)
                    (root = l).red = false;
                else if (pp.right == p)
                    pp.right = l;
                else
                    pp.left = l;
                l.right = p;
                p.parent = l;
            }
            return root;
        }

        static <K,V> TreeNode<K,V> balanceInsertion(TreeNode<K,V> root,
                                                    TreeNode<K,V> x) {
            x.red = true;
            for (TreeNode<K,V> xp, xpp, xppl, xppr;;) {
                if ((xp = x.parent) == null) {
                    x.red = false;
                    return x;
                }
                else if (!xp.red || (xpp = xp.parent) == null)
                    return root;
                if (xp == (xppl = xpp.left)) {
                    if ((xppr = xpp.right) != null && xppr.red) {
                        xppr.red = false;
                        xp.red = false;
                        xpp.red = true;
                        x = xpp;
                    }
                    else {
                        if (x == xp.right) {
                            root = rotateLeft(root, x = xp);
                            xpp = (xp = x.parent) == null ? null : xp.parent;
                        }
                        if (xp != null) {
                            xp.red = false;
                            if (xpp != null) {
                                xpp.red = true;
                                root = rotateRight(root, xpp);
                            }
                        }
                    }
                }
                else {
                    if (xppl != null && xppl.red) {
                        xppl.red = false;
                        xp.red = false;
                        xpp.red = true;
                        x = xpp;
                    }
                    else {
                        if (x == xp.left) {
                            root = rotateRight(root, x = xp);
                            xpp = (xp = x.parent) == null ? null : xp.parent;
                        }
                        if (xp != null) {
                            xp.red = false;
                            if (xpp != null) {
                                xpp.red = true;
                                root = rotateLeft(root, xpp);
                            }
                        }
                    }
                }
            }
        }

        static <K,V> TreeNode<K,V> balanceDeletion(TreeNode<K,V> root,
                                                   TreeNode<K,V> x) {
            for (TreeNode<K,V> xp, xpl, xpr;;)  {
                if (x == null || x == root)
                    return root;
                else if ((xp = x.parent) == null) {
                    x.red = false;
                    return x;
                }
                else if (x.red) {
                    x.red = false;
                    return root;
                }
                else if ((xpl = xp.left) == x) {
                    if ((xpr = xp.right) != null && xpr.red) {
                        xpr.red = false;
                        xp.red = true;
                        root = rotateLeft(root, xp);
                        xpr = (xp = x.parent) == null ? null : xp.right;
                    }
                    if (xpr == null)
                        x = xp;
                    else {
                        TreeNode<K,V> sl = xpr.left, sr = xpr.right;
                        if ((sr == null || !sr.red) &&
                            (sl == null || !sl.red)) {
                            xpr.red = true;
                            x = xp;
                        }
                        else {
                            if (sr == null || !sr.red) {
                                if (sl != null)
                                    sl.red = false;
                                xpr.red = true;
                                root = rotateRight(root, xpr);
                                xpr = (xp = x.parent) == null ?
                                    null : xp.right;
                            }
                            if (xpr != null) {
                                xpr.red = (xp == null) ? false : xp.red;
                                if ((sr = xpr.right) != null)
                                    sr.red = false;
                            }
                            if (xp != null) {
                                xp.red = false;
                                root = rotateLeft(root, xp);
                            }
                            x = root;
                        }
                    }
                }
                else { // symmetric
                    if (xpl != null && xpl.red) {
                        xpl.red = false;
                        xp.red = true;
                        root = rotateRight(root, xp);
                        xpl = (xp = x.parent) == null ? null : xp.left;
                    }
                    if (xpl == null)
                        x = xp;
                    else {
                        TreeNode<K,V> sl = xpl.left, sr = xpl.right;
                        if ((sl == null || !sl.red) &&
                            (sr == null || !sr.red)) {
                            xpl.red = true;
                            x = xp;
                        }
                        else {
                            if (sl == null || !sl.red) {
                                if (sr != null)
                                    sr.red = false;
                                xpl.red = true;
                                root = rotateLeft(root, xpl);
                                xpl = (xp = x.parent) == null ?
                                    null : xp.left;
                            }
                            if (xpl != null) {
                                xpl.red = (xp == null) ? false : xp.red;
                                if ((sl = xpl.left) != null)
                                    sl.red = false;
                            }
                            if (xp != null) {
                                xp.red = false;
                                root = rotateRight(root, xp);
                            }
                            x = root;
                        }
                    }
                }
            }
        }

        /**
         * 给定一个红黑树的节点，验证以该节点作为起点的子树的结构合理性
         */
        static <K,V> boolean checkInvariants(TreeNode<K,V> t) {
            TreeNode<K,V> tp = t.parent, tl = t.left, tr = t.right,
                tb = t.prev, tn = (TreeNode<K,V>)t.next;
            if (tb != null && tb.next != t)
                return false;
            if (tn != null && tn.prev != t)
                return false;
            if (tp != null && t != tp.left && t != tp.right)
                return false;
            if (tl != null && (tl.parent != t || tl.hash > t.hash))
                return false;
            if (tr != null && (tr.parent != t || tr.hash < t.hash))
                return false;
            if (t.red && tl != null && tl.red && tr != null && tr.red)
                return false;
            if (tl != null && !checkInvariants(tl))
                return false;
            if (tr != null && !checkInvariants(tr))
                return false;
            return true;
        }

        private static final sun.misc.Unsafe U;
        private static final long LOCKSTATE;
        static {
            try {
                U = sun.misc.Unsafe.getUnsafe();
                Class<?> k = TreeBin.class;
                LOCKSTATE = U.objectFieldOffset
                    (k.getDeclaredField("lockState"));
            } catch (Exception e) {
                throw new Error(e);
            }
        }
    }

    /* ---------------- 数组遍历 -------------- */

    /**
     * 记录数组，它的长度，以及为一个必须先处理转发数组的一部分的遍历者记录当前正在处理的数组的遍历索引。
     */
    static final class TableStack<K,V> {
        int length;
        int index;
        Node<K,V>[] tab;
        TableStack<K,V> next;
    }

    /**
     * 为某些方法，例如containsValue提供的一个压缩的遍历，同时也被其他迭代器和分割迭代器当作一个基类使用。
     *
     * 方法预先访问一次迭代器构造时可访问的每个仍然有效的节点。这可能会错过一些在访问bin之后添加到bin中的内容，
     * 这对于一致性保证是ok的。在面对可能正在进行的扩容时，这需要相当数量的状态记录，在volatile访问的情况下这很难优化。
     * 即使如此，遍历仍然保持合理的吞吐量。
     *
     * 通常情况下，迭代会逐个地遍历bin。但是如果数组已经扩容了，那么之后的遍历就必须额外遍历那些下标(index + baseSize)处的节点。
     * 如果再次扩容也是同理。为了处理用户跨线程共享迭代器的可能性，如果数组读取的边界检查失败，迭代将终止。
     */
    static class Traverser<K,V> {
        Node<K,V>[] tab;        // 当前的数组，如果扩容了它会被更新
        Node<K,V> next;         // 下一个被使用的条目
        TableStack<K,V> stack, spare; // 为了保存/恢复ForwardingNodes
        int index;              // bin的下一个要被使用的索引
        int baseIndex;          // 初始数组的当前索引
        int baseLimit;          // 初始数组的索引界限
        final int baseSize;     // 初始数组的长度

        Traverser(Node<K,V>[] tab, int size, int index, int limit) {
            this.tab = tab;
            this.baseSize = size;
            this.baseIndex = this.index = index;
            this.baseLimit = limit;
            this.next = null;
        }

        /**
         * 如果可能，将迭代前进一位，返回下一个有效节点，如果没有则返回null。
         */
        final Node<K,V> advance() {
            Node<K,V> e;
            if ((e = next) != null)
                e = e.next;
            for (;;) {
                Node<K,V>[] t; int i, n;  // 必须使用本地变量检查
                if (e != null)
                    return next = e;
                if (baseIndex >= baseLimit || (t = tab) == null ||
                    (n = t.length) <= (i = index) || i < 0)
                    return next = null; // 整个遍历已结束
                if ((e = tabAt(t, i)) != null && e.hash < 0) { // 取index位置的节点
                    if (e instanceof ForwardingNode) { // 下一个节点是个转发节点
                        tab = ((ForwardingNode<K,V>)e).nextTable; // 把参数tab更新为新数组
                        e = null; // 目前遍历到的节点置空
                        pushState(t, i, n); // 保存当前的遍历状态
                        continue; // 中断当前遍历开始遍历转发节点
                    }
                    else if (e instanceof TreeBin) // 如果是树节点，从first元素开始沿着next指针遍历
                        e = ((TreeBin<K,V>)e).first;
                    else
                        e = null;
                }
                if (stack != null) // 尝试恢复刚才中断的遍历（如果有的话）
                    recoverState(n);
                else if ((index = i + baseSize) >= n) // 没有待出栈的遍历，index直接往后跳baseSize长度，为什么跳这么多？
                    // 还是那个定理，扩容后一个bin的位置要么在原地，要么往后推移原数组长度的距离。
                    // 如果推移之后大于等于当前数组长度了，分两种情况：
                    // 1.如果当前在最基础的那个数组中遍历，那么这个条件是恒成立的，直接自增baseIndex和index即可，即取下一个bin。
                    // 2.如果当前在转发节点调度过去的数组中遍历，说明这里面的两个bin已经遍历完了，下一个循环就会将其弹出
                    // 而如果i + baseSize < n，这种情况当且仅当转发调度的数组中从低bin跳到高bin。
                    // ps: 如果中间多扩容了几次，baseSize跨度仍然是初始长度，可能要多跳几次才能找到高位bin
                    index = ++baseIndex;
            }
        }

        /*
            这段出入栈比较抽象，还是举例说明：
                              stack             spare
            pushState(16)     16->null          null
            pushState(32)     32->16->null      null
            pushState(64)     64->32->16->null  null
            recoverState(64)  32->16->null      64->null
            pushState(64)     64->32->16->null  null
            recoverState(64)  16->null          64->32->null
            (有可能一次出栈多个)
            为什么重复利用spare，因为可以想象，如果遍历到中间发生了扩容，则每前进一个bin都会碰到一个转发节点，如果不重复利用，
            最终TableStack的数量会相当恐怖
            ...
         */

        /**
         * 如果遭遇了一个转发节点，则把当前的遍历状态保存起来。
         */
        private void pushState(Node<K,V>[] t, int i, int n) {
            TableStack<K,V> s = spare;  // 重复利用
            if (s != null)
                spare = s.next;
            else
                s = new TableStack<K,V>();
            s.tab = t;
            s.length = n;
            s.index = i;
            s.next = stack;
            stack = s;
        }

        /**
         * 如果可能的话，从栈中弹出之前保存的遍历状态
         * 以recoverState(64)为例
         *
         * @param n 当前数组的长度
         */
        private void recoverState(int n) {
            TableStack<K,V> s; int len;
            while ((s = stack) != null && (index += (len = s.length)) >= n) { // s = stack = 64->32->16->null
                n = len; // n = 64
                index = s.index;
                tab = s.tab;
                s.tab = null;
                TableStack<K,V> next = s.next; // next = 32->16->null
                s.next = spare; // spare = null, s = 64->null
                stack = next; // stack = 32->16->null
                spare = s; // spare = 64 -> null
            }
            // 栈已空，并且这栈里的最后一个数组也处理完了，继续基础数组中的下一个bin
            if (s == null && (index += baseSize) >= n)
                index = ++baseIndex;
        }
    }

    /**
     * key,value,entry迭代器的基类。从Traverser扩展而来，加了一些字段以支持iterator.remove。
     */
    static class BaseIterator<K,V> extends Traverser<K,V> {
        final ConcurrentHashMap<K,V> map;
        Node<K,V> lastReturned;
        BaseIterator(Node<K,V>[] tab, int size, int index, int limit,
                    ConcurrentHashMap<K,V> map) {
            super(tab, size, index, limit);
            this.map = map;
            advance();
        }

        public final boolean hasNext() { return next != null; }
        public final boolean hasMoreElements() { return next != null; }

        public final void remove() {
            Node<K,V> p;
            if ((p = lastReturned) == null)
                throw new IllegalStateException();
            lastReturned = null;
            map.replaceNode(p.key, null, null);
        }
    }

    static final class KeyIterator<K,V> extends BaseIterator<K,V>
        implements Iterator<K>, Enumeration<K> {
        KeyIterator(Node<K,V>[] tab, int index, int size, int limit,
                    ConcurrentHashMap<K,V> map) {
            super(tab, index, size, limit, map);
        }

        public final K next() {
            Node<K,V> p;
            if ((p = next) == null)
                throw new NoSuchElementException();
            K k = p.key;
            lastReturned = p;
            advance();
            return k;
        }

        public final K nextElement() { return next(); }
    }

    static final class ValueIterator<K,V> extends BaseIterator<K,V>
        implements Iterator<V>, Enumeration<V> {
        ValueIterator(Node<K,V>[] tab, int index, int size, int limit,
                      ConcurrentHashMap<K,V> map) {
            super(tab, index, size, limit, map);
        }

        public final V next() {
            Node<K,V> p;
            if ((p = next) == null)
                throw new NoSuchElementException();
            V v = p.val;
            lastReturned = p;
            advance();
            return v;
        }

        public final V nextElement() { return next(); }
    }

    static final class EntryIterator<K,V> extends BaseIterator<K,V>
        implements Iterator<Map.Entry<K,V>> {
        EntryIterator(Node<K,V>[] tab, int index, int size, int limit,
                      ConcurrentHashMap<K,V> map) {
            super(tab, index, size, limit, map);
        }

        public final Map.Entry<K,V> next() {
            Node<K,V> p;
            if ((p = next) == null)
                throw new NoSuchElementException();
            K k = p.key;
            V v = p.val;
            lastReturned = p;
            advance();
            return new MapEntry<K,V>(k, v, map);
        }
    }

    /**
     * Entry迭代器使用的，对外暴露的Entry
     */
    static final class MapEntry<K,V> implements Map.Entry<K,V> {
        final K key; // 非null
        V val;       // 非null
        final ConcurrentHashMap<K,V> map;
        MapEntry(K key, V val, ConcurrentHashMap<K,V> map) {
            this.key = key;
            this.val = val;
            this.map = map;
        }
        public K getKey()        { return key; }
        public V getValue()      { return val; }
        public int hashCode()    { return key.hashCode() ^ val.hashCode(); }
        public String toString() { return key + "=" + val; }

        public boolean equals(Object o) {
            Object k, v; Map.Entry<?,?> e;
            return ((o instanceof Map.Entry) &&
                    (k = (e = (Map.Entry<?,?>)o).getKey()) != null &&
                    (v = e.getValue()) != null &&
                    (k == key || k.equals(key)) &&
                    (v == val || v.equals(val)));
        }

        /**
         * 设置map中对应条目的值。这里的返回值会比较随意，因为我们没有去追踪异步的修改，实际上最新的那个"旧值"可能不是我们返回的这个值，
         * 甚至可能是被删除掉的，这种情况下这个键值对会被重建（重建为我们放入的值）。
         * 总而言之就是返回值不可靠。
         */
        public V setValue(V value) {
            if (value == null) throw new NullPointerException();
            V v = val;
            val = value;
            map.put(key, value);
            return v;
        }
    }

    static final class KeySpliterator<K,V> extends Traverser<K,V>
        implements Spliterator<K> {
        long est;               // 容量估计
        KeySpliterator(Node<K,V>[] tab, int size, int index, int limit,
                       long est) {
            super(tab, size, index, limit);
            this.est = est;
        }

        public Spliterator<K> trySplit() {
            int i, f, h;
            return (h = ((i = baseIndex) + (f = baseLimit)) >>> 1) <= i ? null :
                new KeySpliterator<K,V>(tab, baseSize, baseLimit = h,
                                        f, est >>>= 1);
        }

        public void forEachRemaining(Consumer<? super K> action) {
            if (action == null) throw new NullPointerException();
            for (Node<K,V> p; (p = advance()) != null;)
                action.accept(p.key);
        }

        public boolean tryAdvance(Consumer<? super K> action) {
            if (action == null) throw new NullPointerException();
            Node<K,V> p;
            if ((p = advance()) == null)
                return false;
            action.accept(p.key);
            return true;
        }

        public long estimateSize() { return est; }

        public int characteristics() {
            return Spliterator.DISTINCT | Spliterator.CONCURRENT |
                Spliterator.NONNULL;
        }
    }

    static final class ValueSpliterator<K,V> extends Traverser<K,V>
        implements Spliterator<V> {
        long est;               // size estimate
        ValueSpliterator(Node<K,V>[] tab, int size, int index, int limit,
                         long est) {
            super(tab, size, index, limit);
            this.est = est;
        }

        public Spliterator<V> trySplit() {
            int i, f, h;
            return (h = ((i = baseIndex) + (f = baseLimit)) >>> 1) <= i ? null :
                new ValueSpliterator<K,V>(tab, baseSize, baseLimit = h,
                                          f, est >>>= 1);
        }

        public void forEachRemaining(Consumer<? super V> action) {
            if (action == null) throw new NullPointerException();
            for (Node<K,V> p; (p = advance()) != null;)
                action.accept(p.val);
        }

        public boolean tryAdvance(Consumer<? super V> action) {
            if (action == null) throw new NullPointerException();
            Node<K,V> p;
            if ((p = advance()) == null)
                return false;
            action.accept(p.val);
            return true;
        }

        public long estimateSize() { return est; }

        public int characteristics() {
            return Spliterator.CONCURRENT | Spliterator.NONNULL;
        }
    }

    static final class EntrySpliterator<K,V> extends Traverser<K,V>
        implements Spliterator<Map.Entry<K,V>> {
        final ConcurrentHashMap<K,V> map; // 为了提供给MapEntry
        long est;               // 容量估计
        EntrySpliterator(Node<K,V>[] tab, int size, int index, int limit,
                         long est, ConcurrentHashMap<K,V> map) {
            super(tab, size, index, limit);
            this.map = map;
            this.est = est;
        }

        public Spliterator<Map.Entry<K,V>> trySplit() {
            int i, f, h;
            return (h = ((i = baseIndex) + (f = baseLimit)) >>> 1) <= i ? null :
                new EntrySpliterator<K,V>(tab, baseSize, baseLimit = h,
                                          f, est >>>= 1, map);
        }

        public void forEachRemaining(Consumer<? super Map.Entry<K,V>> action) {
            if (action == null) throw new NullPointerException();
            for (Node<K,V> p; (p = advance()) != null; )
                action.accept(new MapEntry<K,V>(p.key, p.val, map));
        }

        public boolean tryAdvance(Consumer<? super Map.Entry<K,V>> action) {
            if (action == null) throw new NullPointerException();
            Node<K,V> p;
            if ((p = advance()) == null)
                return false;
            action.accept(new MapEntry<K,V>(p.key, p.val, map));
            return true;
        }

        public long estimateSize() { return est; }

        public int characteristics() {
            return Spliterator.DISTINCT | Spliterator.CONCURRENT |
                Spliterator.NONNULL;
        }
    }

    // 并行的批量操作
    // TODO
    /**
     * 该方法会计算出批量任务的初始批量值。这个初始批量值会在每次对半分割出子任务时被除以2，值为0时则不再分割任务。
     * 传入值b为并行度阈值，如果b太大，或者当前map只有1个元素，或者当前map中的元素数量小于b，则直接返回0，代表这次批量任务不会再被分割成子任务。
     * ForkJoinPool.getCommonPoolParallelism()方法获取当前通用线程池的并行度，这个值会作为参考，如果参考值满足不了传入的并行度阈值，
     * 则使用这个限制值，否则就会按照要求将(元素数量/b)作为最终的初始批量值。
     */
    final int batchFor(long b) {
        long n;
        if (b == Long.MAX_VALUE || (n = sumCount()) <= 1L || n < b)
            return 0;
        int sp = ForkJoinPool.getCommonPoolParallelism() << 2; // 获取通用线程池的并行度，膨胀4倍
        return (b <= 0L || (n /= b) >= sp) ? sp : (int)n;
    }

    /**
     * 以key，value为入参执行给定的action方法
     *
     * @param parallelismThreshold 需要被并行处理的元素数量的预估值
     * @param action 指定的方法
     * @since 1.8
     */
    public void forEach(long parallelismThreshold,
                        BiConsumer<? super K,? super V> action) {
        if (action == null) throw new NullPointerException();
        new ForEachMappingTask<K,V>
            (null, batchFor(parallelismThreshold), 0, 0, table,
             action).invoke();
    }

    /**
     * 遍历整个map，将key和value作为参数传入transformer方法得到结果u，如果结果u不为null，则以u为入参执行action
     *
     * @param parallelismThreshold 需要被并行处理的元素的预估数量
     * @param transformer 转化key的方法
     * @param action 将转化后的key作为入参执行的方法
     * @param <U> 转化key的方法返回值的类型
     * @since 1.8
     */
    public <U> void forEach(long parallelismThreshold,
                            BiFunction<? super K, ? super V, ? extends U> transformer,
                            Consumer<? super U> action) {
        if (transformer == null || action == null)
            throw new NullPointerException();
        new ForEachTransformedMappingTask<K,V,U>
            (null, batchFor(parallelismThreshold), 0, 0, table,
             transformer, action).invoke();
    }

    /**
     * 遍历整个map，将key和value作为参数传入searchFunction方法得到结果u，如果结果u不为null，则结束所有任务并返回u，否则继续遍历。
     *
     * @param parallelismThreshold 需要被并行处理的元素的预估数量
     * @param searchFunction 一个入参为key和value的方法，如果结果不为null则视为搜索到了元素
     * @param <U> searchFunction 方法的返回值类型
     * @return 如果搜索到了则返回计算结果，否则返回null
     * @since 1.8
     */
    public <U> U search(long parallelismThreshold,
                        BiFunction<? super K, ? super V, ? extends U> searchFunction) {
        if (searchFunction == null) throw new NullPointerException();
        return new SearchMappingsTask<K,V,U>
            (null, batchFor(parallelismThreshold), 0, 0, table,
             searchFunction, new AtomicReference<U>()).invoke();
    }

    /**
     * 遍历map，将key和value作为入参执行transformer，将每一个键值对计算得到的结果聚合，返回值是聚合结果
     */
    public <U> U reduce(long parallelismThreshold,
                        BiFunction<? super K, ? super V, ? extends U> transformer,
                        BiFunction<? super U, ? super U, ? extends U> reducer) {
        if (transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceMappingsTask<K,V,U>
            (null, batchFor(parallelismThreshold), 0, 0, table,
             null, transformer, reducer).invoke();
    }

    /**
     * 遍历map，将key和value作为入参执行transformer，将每一个键值对计算得到的结果聚合，返回值是聚合结果。
     * 并且，聚合的结果是double型
     * basis是聚合起点值
     */
    public double reduceToDouble(long parallelismThreshold,
                                 ToDoubleBiFunction<? super K, ? super V> transformer,
                                 double basis,
                                 DoubleBinaryOperator reducer) {
        if (transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceMappingsToDoubleTask<K,V>
            (null, batchFor(parallelismThreshold), 0, 0, table,
             null, transformer, basis, reducer).invoke();
    }

    /**
     * 遍历map，将key和value作为入参执行transformer，将每一个键值对计算得到的结果聚合，返回值是聚合结果
     * 并且，聚合的结果是double型
     * basis是聚合起点值
     */
    public long reduceToLong(long parallelismThreshold,
                             ToLongBiFunction<? super K, ? super V> transformer,
                             long basis,
                             LongBinaryOperator reducer) {
        if (transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceMappingsToLongTask<K,V>
            (null, batchFor(parallelismThreshold), 0, 0, table,
             null, transformer, basis, reducer).invoke();
    }

    /**
     * 遍历map，将key和value作为入参执行transformer，将每一个键值对计算得到的结果聚合，返回值是聚合结果
     * 并且，聚合的结果是int型
     * basis是聚合起点值
     */
    public int reduceToInt(long parallelismThreshold,
                           ToIntBiFunction<? super K, ? super V> transformer,
                           int basis,
                           IntBinaryOperator reducer) {
        if (transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceMappingsToIntTask<K,V>
            (null, batchFor(parallelismThreshold), 0, 0, table,
             null, transformer, basis, reducer).invoke();
    }

    /**
     * 遍历整个map，将key作为参数传入action依次执行
     *
     * @param parallelismThreshold 需要被并行处理的元素的预估数量
     * @param action 指定的方法
     * @since 1.8
     */
    public void forEachKey(long parallelismThreshold,
                           Consumer<? super K> action) {
        if (action == null) throw new NullPointerException();
        new ForEachKeyTask<K,V>
            (null, batchFor(parallelismThreshold), 0, 0, table,
             action).invoke();
    }

    /**
     * 遍历整个map，将key作为参数传入transformer方法得到结果u，如果结果u不为null，则以u为入参执行action
     *
     * @param parallelismThreshold 需要被并行处理的元素的预估数量
     * @param transformer 转化key的方法
     * @param action 将转化后的key作为入参执行的方法
     * @param <U> 转化key的方法返回值的类型
     * @since 1.8
     */
    public <U> void forEachKey(long parallelismThreshold,
                               Function<? super K, ? extends U> transformer,
                               Consumer<? super U> action) {
        if (transformer == null || action == null)
            throw new NullPointerException();
        new ForEachTransformedKeyTask<K,V,U>
            (null, batchFor(parallelismThreshold), 0, 0, table,
             transformer, action).invoke();
    }

    /**
     * 遍历整个map，搜索一个这样的key：以key为入参执行searchFunction结果不为null。
     * 如果搜索到了，则返回执行searchFunction的结果，否则返回null。
     */
    public <U> U searchKeys(long parallelismThreshold,
                            Function<? super K, ? extends U> searchFunction) {
        if (searchFunction == null) throw new NullPointerException();
        return new SearchKeysTask<K,V,U>
            (null, batchFor(parallelismThreshold), 0, 0, table,
             searchFunction, new AtomicReference<U>()).invoke();
    }

    /**
     * 遍历整个map，将所有key以reducer方法聚合，返回聚合结果
     */
    public K reduceKeys(long parallelismThreshold,
                        BiFunction<? super K, ? super K, ? extends K> reducer) {
        if (reducer == null) throw new NullPointerException();
        return new ReduceKeysTask<K,V>
            (null, batchFor(parallelismThreshold), 0, 0, table,
             null, reducer).invoke();
    }

    /**
     * 遍历整个map，将所有key以transformer计算得到结果，再将结果用reducer聚合，返回最终的聚合结果
     */
    public <U> U reduceKeys(long parallelismThreshold,
                            Function<? super K, ? extends U> transformer,
         BiFunction<? super U, ? super U, ? extends U> reducer) {
        if (transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceKeysTask<K,V,U>
            (null, batchFor(parallelismThreshold), 0, 0, table,
             null, transformer, reducer).invoke();
    }

    /**
     * 遍历整个map，将所有key以transformer计算得到结果，再将结果用reducer聚合，返回最终的聚合结果
     * 并且，聚合的结果是double型
     * basis是聚合起点值
     */
    public double reduceKeysToDouble(long parallelismThreshold,
                                     ToDoubleFunction<? super K> transformer,
                                     double basis,
                                     DoubleBinaryOperator reducer) {
        if (transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceKeysToDoubleTask<K,V>
            (null, batchFor(parallelismThreshold), 0, 0, table,
             null, transformer, basis, reducer).invoke();
    }

    /**
     * 遍历整个map，将所有key以transformer计算得到结果，再将结果用reducer聚合，返回最终的聚合结果
     * 并且，聚合的结果是long型
     * basis是聚合起点值
     */
    public long reduceKeysToLong(long parallelismThreshold,
                                 ToLongFunction<? super K> transformer,
                                 long basis,
                                 LongBinaryOperator reducer) {
        if (transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceKeysToLongTask<K,V>
            (null, batchFor(parallelismThreshold), 0, 0, table,
             null, transformer, basis, reducer).invoke();
    }

    /**
     * 遍历整个map，将所有key以transformer计算得到结果，再将结果用reducer聚合，返回最终的聚合结果
     * 并且，聚合的结果是int型
     * basis是聚合起点值
     */
    public int reduceKeysToInt(long parallelismThreshold,
                               ToIntFunction<? super K> transformer,
                               int basis,
                               IntBinaryOperator reducer) {
        if (transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceKeysToIntTask<K,V>
            (null, batchFor(parallelismThreshold), 0, 0, table,
             null, transformer, basis, reducer).invoke();
    }

    /**
     * 遍历整个map，对每个value作为入参执行action方法
     */
    public void forEachValue(long parallelismThreshold,
                             Consumer<? super V> action) {
        if (action == null)
            throw new NullPointerException();
        new ForEachValueTask<K,V>
            (null, batchFor(parallelismThreshold), 0, 0, table,
             action).invoke();
    }

    /**
     * 遍历整个map，对每个value作为入参执行transformer方法得到结果，如果结果不为null，则把结果作为入参执行action
     */
    public <U> void forEachValue(long parallelismThreshold,
                                 Function<? super V, ? extends U> transformer,
                                 Consumer<? super U> action) {
        if (transformer == null || action == null)
            throw new NullPointerException();
        new ForEachTransformedValueTask<K,V,U>
            (null, batchFor(parallelismThreshold), 0, 0, table,
             transformer, action).invoke();
    }

    /**
     * 遍历整个map，搜索一个这样的value：以value为入参执行searchFunction结果不为null。
     * 如果搜索到了，则返回执行searchFunction的结果，否则返回null。
     */
    public <U> U searchValues(long parallelismThreshold,
                              Function<? super V, ? extends U> searchFunction) {
        if (searchFunction == null) throw new NullPointerException();
        return new SearchValuesTask<K,V,U>
            (null, batchFor(parallelismThreshold), 0, 0, table,
             searchFunction, new AtomicReference<U>()).invoke();
    }

    /**
     * 遍历整个map，将所有value用reducer聚合，返回最终的聚合结果
     * 并且，聚合的结果是double型
     * basis是聚合起点值
     */
    public V reduceValues(long parallelismThreshold,
                          BiFunction<? super V, ? super V, ? extends V> reducer) {
        if (reducer == null) throw new NullPointerException();
        return new ReduceValuesTask<K,V>
            (null, batchFor(parallelismThreshold), 0, 0, table,
             null, reducer).invoke();
    }

    /**
     * 遍历整个map，将所有value以transformer计算得到结果，再将结果用reducer聚合，返回最终的聚合结果
     */
    public <U> U reduceValues(long parallelismThreshold,
                              Function<? super V, ? extends U> transformer,
                              BiFunction<? super U, ? super U, ? extends U> reducer) {
        if (transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceValuesTask<K,V,U>
            (null, batchFor(parallelismThreshold), 0, 0, table,
             null, transformer, reducer).invoke();
    }

    /**
     * 遍历整个map，将所有value以transformer计算得到结果，再将结果用reducer聚合，返回最终的聚合结果
     * 并且，聚合的结果是double型
     * basis是聚合起点值
     */
    public double reduceValuesToDouble(long parallelismThreshold,
                                       ToDoubleFunction<? super V> transformer,
                                       double basis,
                                       DoubleBinaryOperator reducer) {
        if (transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceValuesToDoubleTask<K,V>
            (null, batchFor(parallelismThreshold), 0, 0, table,
             null, transformer, basis, reducer).invoke();
    }

    /**
     * 遍历整个map，将所有value以transformer计算得到结果，再将结果用reducer聚合，返回最终的聚合结果
     * 并且，聚合的结果是long型
     * basis是聚合起点值
     */
    public long reduceValuesToLong(long parallelismThreshold,
                                   ToLongFunction<? super V> transformer,
                                   long basis,
                                   LongBinaryOperator reducer) {
        if (transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceValuesToLongTask<K,V>
            (null, batchFor(parallelismThreshold), 0, 0, table,
             null, transformer, basis, reducer).invoke();
    }

    /**
     * 遍历整个map，将所有value以transformer计算得到结果，再将结果用reducer聚合，返回最终的聚合结果
     * 并且，聚合的结果是int型
     * basis是聚合起点值
     */
    public int reduceValuesToInt(long parallelismThreshold,
                                 ToIntFunction<? super V> transformer,
                                 int basis,
                                 IntBinaryOperator reducer) {
        if (transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceValuesToIntTask<K,V>
            (null, batchFor(parallelismThreshold), 0, 0, table,
             null, transformer, basis, reducer).invoke();
    }

    /**
     * 遍历map，将每个Entry作为入参执行action方法
     */
    public void forEachEntry(long parallelismThreshold,
                             Consumer<? super Map.Entry<K,V>> action) {
        if (action == null) throw new NullPointerException();
        new ForEachEntryTask<K,V>(null, batchFor(parallelismThreshold), 0, 0, table,
                                  action).invoke();
    }

    /**
     * 遍历整个map，对每个Entry作为入参执行transformer方法得到结果，如果结果不为null，则把结果作为入参执行action
     */
    public <U> void forEachEntry(long parallelismThreshold,
                                 Function<Map.Entry<K,V>, ? extends U> transformer,
                                 Consumer<? super U> action) {
        if (transformer == null || action == null)
            throw new NullPointerException();
        new ForEachTransformedEntryTask<K,V,U>
            (null, batchFor(parallelismThreshold), 0, 0, table,
             transformer, action).invoke();
    }

    /**
     * 遍历整个map，搜索一个这样的Entry：以Entry为入参执行searchFunction结果不为null。
     * 如果搜索到了，则返回执行searchFunction的结果，否则返回null。
     */
    public <U> U searchEntries(long parallelismThreshold,
                               Function<Map.Entry<K,V>, ? extends U> searchFunction) {
        if (searchFunction == null) throw new NullPointerException();
        return new SearchEntriesTask<K,V,U>
            (null, batchFor(parallelismThreshold), 0, 0, table,
             searchFunction, new AtomicReference<U>()).invoke();
    }

    /**
     * 遍历整个map，将所有Entry用reducer聚合，返回最终的聚合结果
     */
    public Map.Entry<K,V> reduceEntries(long parallelismThreshold,
                                        BiFunction<Map.Entry<K,V>, Map.Entry<K,V>, ? extends Map.Entry<K,V>> reducer) {
        if (reducer == null) throw new NullPointerException();
        return new ReduceEntriesTask<K,V>
            (null, batchFor(parallelismThreshold), 0, 0, table,
             null, reducer).invoke();
    }

    /**
     * 遍历整个map，将所有Entry以transformer计算得到结果，再将结果用reducer聚合，返回最终的聚合结果
     */
    public <U> U reduceEntries(long parallelismThreshold,
                               Function<Map.Entry<K,V>, ? extends U> transformer,
                               BiFunction<? super U, ? super U, ? extends U> reducer) {
        if (transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceEntriesTask<K,V,U>
            (null, batchFor(parallelismThreshold), 0, 0, table,
             null, transformer, reducer).invoke();
    }

    /**
     * 遍历整个map，将所有Entry以transformer计算得到结果，再将结果用reducer聚合，返回最终的聚合结果
     * 并且，聚合的结果是double型
     * basis是聚合起点值
     */
    public double reduceEntriesToDouble(long parallelismThreshold,
                                        ToDoubleFunction<Map.Entry<K,V>> transformer,
                                        double basis,
                                        DoubleBinaryOperator reducer) {
        if (transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceEntriesToDoubleTask<K,V>
            (null, batchFor(parallelismThreshold), 0, 0, table,
             null, transformer, basis, reducer).invoke();
    }

    /**
     * 遍历整个map，将所有Entry以transformer计算得到结果，再将结果用reducer聚合，返回最终的聚合结果
     * 并且，聚合的结果是long型
     * basis是聚合起点值
     */
    public long reduceEntriesToLong(long parallelismThreshold,
                                    ToLongFunction<Map.Entry<K,V>> transformer,
                                    long basis,
                                    LongBinaryOperator reducer) {
        if (transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceEntriesToLongTask<K,V>
            (null, batchFor(parallelismThreshold), 0, 0, table,
             null, transformer, basis, reducer).invoke();
    }

    /**
     * 遍历整个map，将所有Entry以transformer计算得到结果，再将结果用reducer聚合，返回最终的聚合结果
     * 并且，聚合的结果是int型
     * basis是聚合起点值
     */
    public int reduceEntriesToInt(long parallelismThreshold,
                                  ToIntFunction<Map.Entry<K,V>> transformer,
                                  int basis,
                                  IntBinaryOperator reducer) {
        if (transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceEntriesToIntTask<K,V>
            (null, batchFor(parallelismThreshold), 0, 0, table,
             null, transformer, basis, reducer).invoke();
    }


    /* ---------------- 视图 -------------- */

    /**
     * 视图的基类
     */
    abstract static class CollectionView<K,V,E>
        implements Collection<E>, java.io.Serializable {
        private static final long serialVersionUID = 7249069246763182397L;
        final ConcurrentHashMap<K,V> map;
        CollectionView(ConcurrentHashMap<K,V> map)  { this.map = map; }

        /**
         * 返回该视图持有的map
         *
         * @return 该视图持有的map
         */
        public ConcurrentHashMap<K,V> getMap() { return map; }

        /**
         * 通过调用所持有的map的clear方法清除所有节点
         */
        public final void clear()      { map.clear(); }
        public final int size()        { return map.size(); }
        public final boolean isEmpty() { return map.isEmpty(); }

        // 下面几个方法在继承这个抽象类的实际类中实现
        /**
         * 返回该集合的一个迭代器，返回的迭代器是弱一致性的
         *
         * @return 该集合的一个迭代器
         */
        public abstract Iterator<E> iterator();
        public abstract boolean contains(Object o);
        public abstract boolean remove(Object o);

        private static final String oomeMsg = "Required array size too large";

        public final Object[] toArray() {
            long sz = map.mappingCount();
            if (sz > MAX_ARRAY_SIZE)
                throw new OutOfMemoryError(oomeMsg);
            int n = (int)sz;
            Object[] r = new Object[n];
            int i = 0;
            for (E e : this) {
                if (i == n) { // 已经遍历完了预计的n个节点，但还有节点，说明在迭代过程中map中又有新节点进来了。进行对应的扩容并建新数组
                    if (n >= MAX_ARRAY_SIZE)
                        throw new OutOfMemoryError(oomeMsg);
                    if (n >= MAX_ARRAY_SIZE - (MAX_ARRAY_SIZE >>> 1) - 1)
                        n = MAX_ARRAY_SIZE;
                    else
                        n += (n >>> 1) + 1;
                    r = Arrays.copyOf(r, n);
                }
                r[i++] = e;
            }
            // 如果转化过程中节点数量没变，则直接返回原数组，否则按照实际数量剪切数组（如果不剪切会导致获取到的数组含有一堆null元素）
            return (i == n) ? r : Arrays.copyOf(r, i);
        }

        /**
         * 与上一个方法类似，但可以传入一个数组a，如果a足够长那么返回的仍然是a，a的前半部分元素被覆盖为map中的所有节点，
         * 后半部分多的元素被置为null。如果a太短，返回的就是一个新的足够容纳所有节点的数组。
         */
        @SuppressWarnings("unchecked")
        public final <T> T[] toArray(T[] a) {
            long sz = map.mappingCount();
            if (sz > MAX_ARRAY_SIZE)
                throw new OutOfMemoryError(oomeMsg);
            int m = (int)sz;
            T[] r = (a.length >= m) ? a :
                (T[])java.lang.reflect.Array
                .newInstance(a.getClass().getComponentType(), m);
            int n = r.length;
            int i = 0;
            for (E e : this) {
                if (i == n) {
                    if (n >= MAX_ARRAY_SIZE)
                        throw new OutOfMemoryError(oomeMsg);
                    if (n >= MAX_ARRAY_SIZE - (MAX_ARRAY_SIZE >>> 1) - 1)
                        n = MAX_ARRAY_SIZE;
                    else
                        n += (n >>> 1) + 1;
                    r = Arrays.copyOf(r, n);
                }
                r[i++] = (T)e;
            }
            if (a == r && i < n) {
                r[i] = null; // 如果给的a数组很长，这里就会有一些多的格子，把这些格子置空
                return r;
            }
            return (i == n) ? r : Arrays.copyOf(r, i);
        }

        /**
         * 返回该集合的字符串形式。
         * 字符串由实际实现类的迭代器返回的元素组成，外面用"[]"包起来，里面用", "（半角逗号和空格）隔开。
         * 元素是像String.valueOf(Object)那样转化好的
         *
         * @return 该集合的字符串形式
         */
        public final String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            Iterator<E> it = iterator();
            if (it.hasNext()) {
                for (;;) {
                    Object e = it.next();
                    sb.append(e == this ? "(this Collection)" : e);
                    if (!it.hasNext())
                        break;
                    sb.append(',').append(' ');
                }
            }
            return sb.append(']').toString();
        }

        public final boolean containsAll(Collection<?> c) {
            if (c != this) {
                for (Object e : c) {
                    if (e == null || !contains(e))
                        return false;
                }
            }
            return true;
        }

        public final boolean removeAll(Collection<?> c) {
            if (c == null) throw new NullPointerException();
            boolean modified = false;
            for (Iterator<E> it = iterator(); it.hasNext();) {
                if (c.contains(it.next())) {
                    it.remove();
                    modified = true;
                }
            }
            return modified;
        }

        public final boolean retainAll(Collection<?> c) {
            if (c == null) throw new NullPointerException();
            boolean modified = false;
            for (Iterator<E> it = iterator(); it.hasNext();) {
                if (!c.contains(it.next())) {
                    it.remove();
                    modified = true;
                }
            }
            return modified;
        }

    }

    /**
     * 所有key的一个集合视图。不能直接被实例化。
     * See {@link #keySet() keySet()},
     * {@link #keySet(Object) keySet(V)},
     * {@link #newKeySet() newKeySet()},
     * {@link #newKeySet(int) newKeySet(int)}.
     *
     * @since 1.8
     */
    public static class KeySetView<K,V> extends CollectionView<K,V,K>
        implements Set<K>, java.io.Serializable {
        private static final long serialVersionUID = 7249069246763182397L;
        private final V value;
        KeySetView(ConcurrentHashMap<K,V> map, V value) {  // non-public
            super(map);
            this.value = value;
        }

        /**
         * 返回一个额外的默认映射值，如果不支持额外的映射值，则返回null。
         */
        public V getMappedValue() { return value; }

        /**
         * 判断map中是否存在value为指定的o
         */
        public boolean contains(Object o) { return map.containsKey(o); }

        /**
         * 指定一个key，从该集合和源map中删除掉该key对应的节点，调用的是源map的remove方法
         */
        public boolean remove(Object o) { return map.remove(o) != null; }

        /**
         * @return 返回源包含map中所有key的一个迭代器
         */
        public Iterator<K> iterator() {
            Node<K,V>[] t;
            ConcurrentHashMap<K,V> m = map;
            int f = (t = m.table) == null ? 0 : t.length;
            return new KeyIterator<K,V>(t, f, 0, f, m);
        }

        /**
         * 将指定的key和默认值作为键值对插入map，如果当前视图没有指定默认值则会报错，如果key已存在，那么值不会被覆盖。
         * 如果key成功放入了则返回true
         */
        public boolean add(K e) {
            V v;
            if ((v = value) == null)
                throw new UnsupportedOperationException();
            return map.putVal(e, v, true) == null;
        }

        /**
         * 同上一个方法，且只要有一个key成功放入了就会返回true
         */
        public boolean addAll(Collection<? extends K> c) {
            boolean added = false;
            V v;
            if ((v = value) == null)
                throw new UnsupportedOperationException();
            for (K e : c) {
                if (map.putVal(e, v, true) == null)
                    added = true;
            }
            return added;
        }

        public int hashCode() {
            int h = 0;
            for (K e : this)
                h += e.hashCode();
            return h;
        }

        public boolean equals(Object o) {
            Set<?> c;
            return ((o instanceof Set) &&
                    ((c = (Set<?>)o) == this ||
                     (containsAll(c) && c.containsAll(this))));
        }

        public Spliterator<K> spliterator() {
            Node<K,V>[] t;
            ConcurrentHashMap<K,V> m = map;
            long n = m.sumCount();
            int f = (t = m.table) == null ? 0 : t.length;
            return new KeySpliterator<K,V>(t, f, 0, f, n < 0L ? 0L : n);
        }

        public void forEach(Consumer<? super K> action) {
            if (action == null) throw new NullPointerException();
            Node<K,V>[] t;
            if ((t = map.table) != null) {
                Traverser<K,V> it = new Traverser<K,V>(t, t.length, 0, t.length);
                for (Node<K,V> p; (p = it.advance()) != null; )
                    action.accept(p.key);
            }
        }
    }

    /**
     * 所有value的一个集合视图。不能直接被实例化。
     */
    static final class ValuesView<K,V> extends CollectionView<K,V,V>
        implements Collection<V>, java.io.Serializable {
        private static final long serialVersionUID = 2249069246763182397L;
        ValuesView(ConcurrentHashMap<K,V> map) { super(map); }
        public final boolean contains(Object o) {
            return map.containsValue(o);
        }

        public final boolean remove(Object o) {
            if (o != null) {
                for (Iterator<V> it = iterator(); it.hasNext();) {
                    if (o.equals(it.next())) {
                        it.remove();
                        return true;
                    }
                }
            }
            return false;
        }

        public final Iterator<V> iterator() {
            ConcurrentHashMap<K,V> m = map;
            Node<K,V>[] t;
            int f = (t = m.table) == null ? 0 : t.length;
            return new ValueIterator<K,V>(t, f, 0, f, m);
        }

        public final boolean add(V e) {
            throw new UnsupportedOperationException();
        }
        public final boolean addAll(Collection<? extends V> c) {
            throw new UnsupportedOperationException();
        }

        public Spliterator<V> spliterator() {
            Node<K,V>[] t;
            ConcurrentHashMap<K,V> m = map;
            long n = m.sumCount();
            int f = (t = m.table) == null ? 0 : t.length;
            return new ValueSpliterator<K,V>(t, f, 0, f, n < 0L ? 0L : n);
        }

        public void forEach(Consumer<? super V> action) {
            if (action == null) throw new NullPointerException();
            Node<K,V>[] t;
            if ((t = map.table) != null) {
                Traverser<K,V> it = new Traverser<K,V>(t, t.length, 0, t.length);
                for (Node<K,V> p; (p = it.advance()) != null; )
                    action.accept(p.val);
            }
        }
    }

    /**
     * 所有Entry的一个集合视图。不能直接被实例化。
     */
    static final class EntrySetView<K,V> extends CollectionView<K,V,Map.Entry<K,V>>
        implements Set<Map.Entry<K,V>>, java.io.Serializable {
        private static final long serialVersionUID = 2249069246763182397L;
        EntrySetView(ConcurrentHashMap<K,V> map) { super(map); }

        public boolean contains(Object o) {
            Object k, v, r; Map.Entry<?,?> e;
            return ((o instanceof Map.Entry) &&
                    (k = (e = (Map.Entry<?,?>)o).getKey()) != null &&
                    (r = map.get(k)) != null &&
                    (v = e.getValue()) != null &&
                    (v == r || v.equals(r)));
        }

        public boolean remove(Object o) {
            Object k, v; Map.Entry<?,?> e;
            return ((o instanceof Map.Entry) &&
                    (k = (e = (Map.Entry<?,?>)o).getKey()) != null &&
                    (v = e.getValue()) != null &&
                    map.remove(k, v));
        }

        public Iterator<Map.Entry<K,V>> iterator() {
            ConcurrentHashMap<K,V> m = map;
            Node<K,V>[] t;
            int f = (t = m.table) == null ? 0 : t.length;
            return new EntryIterator<K,V>(t, f, 0, f, m);
        }

        public boolean add(Entry<K,V> e) {
            return map.putVal(e.getKey(), e.getValue(), false) == null;
        }

        public boolean addAll(Collection<? extends Entry<K,V>> c) {
            boolean added = false;
            for (Entry<K,V> e : c) {
                if (add(e))
                    added = true;
            }
            return added;
        }

        public final int hashCode() {
            int h = 0;
            Node<K,V>[] t;
            if ((t = map.table) != null) {
                Traverser<K,V> it = new Traverser<K,V>(t, t.length, 0, t.length);
                for (Node<K,V> p; (p = it.advance()) != null; ) {
                    h += p.hashCode();
                }
            }
            return h;
        }

        public final boolean equals(Object o) {
            Set<?> c;
            return ((o instanceof Set) &&
                    ((c = (Set<?>)o) == this ||
                     (containsAll(c) && c.containsAll(this))));
        }

        public Spliterator<Map.Entry<K,V>> spliterator() {
            Node<K,V>[] t;
            ConcurrentHashMap<K,V> m = map;
            long n = m.sumCount();
            int f = (t = m.table) == null ? 0 : t.length;
            return new EntrySpliterator<K,V>(t, f, 0, f, n < 0L ? 0L : n, m);
        }

        public void forEach(Consumer<? super Map.Entry<K,V>> action) {
            if (action == null) throw new NullPointerException();
            Node<K,V>[] t;
            if ((t = map.table) != null) {
                Traverser<K,V> it = new Traverser<K,V>(t, t.length, 0, t.length);
                for (Node<K,V> p; (p = it.advance()) != null; )
                    action.accept(new MapEntry<K,V>(p.key, p.val, map));
            }
        }

    }

    // -------------------------------------------------------

    /**
     * 批量任务的基类。部分字段和代码和Traverser类相同，因为我们需要去扩展CountedCompleter
     */
    @SuppressWarnings("serial")
    abstract static class BulkTask<K,V,R> extends CountedCompleter<R> {
        Node<K,V>[] tab;        // 和Traverser相同
        Node<K,V> next;
        TableStack<K,V> stack, spare;
        int index;
        int baseIndex;
        int baseLimit;
        final int baseSize;
        int batch;              // 划分控制

        BulkTask(BulkTask<K,V,?> par, int b, int i, int f, Node<K,V>[] t) {
            super(par);
            this.batch = b;
            this.index = this.baseIndex = i;
            if ((this.tab = t) == null)
                this.baseSize = this.baseLimit = 0;
            else if (par == null)
                this.baseSize = this.baseLimit = t.length;
            else {
                this.baseLimit = f;
                this.baseSize = par.baseSize;
            }
        }

        /**
         * 和Traverser版本的advance()一样
         */
        final Node<K,V> advance() {
            Node<K,V> e;
            if ((e = next) != null)
                e = e.next;
            for (;;) {
                Node<K,V>[] t; int i, n;
                if (e != null)
                    return next = e;
                if (baseIndex >= baseLimit || (t = tab) == null ||
                    (n = t.length) <= (i = index) || i < 0)
                    return next = null;
                if ((e = tabAt(t, i)) != null && e.hash < 0) {
                    if (e instanceof ForwardingNode) {
                        tab = ((ForwardingNode<K,V>)e).nextTable;
                        e = null;
                        pushState(t, i, n);
                        continue;
                    }
                    else if (e instanceof TreeBin)
                        e = ((TreeBin<K,V>)e).first;
                    else
                        e = null;
                }
                if (stack != null)
                    recoverState(n);
                else if ((index = i + baseSize) >= n)
                    index = ++baseIndex;
            }
        }

        private void pushState(Node<K,V>[] t, int i, int n) {
            TableStack<K,V> s = spare;
            if (s != null)
                spare = s.next;
            else
                s = new TableStack<K,V>();
            s.tab = t;
            s.length = n;
            s.index = i;
            s.next = stack;
            stack = s;
        }

        private void recoverState(int n) {
            TableStack<K,V> s; int len;
            while ((s = stack) != null && (index += (len = s.length)) >= n) {
                n = len;
                index = s.index;
                tab = s.tab;
                s.tab = null;
                TableStack<K,V> next = s.next;
                s.next = spare; // save for reuse
                stack = next;
                spare = s;
            }
            if (s == null && (index += baseSize) >= n)
                index = ++baseIndex;
        }
    }

    /*
     * 一组任务类。用一种比较规则但简陋的方式组建，对传入的action方法也进行了null判断，以免发生奇怪的问题。
     * 有些方法逻辑比较相似，所以只有部分给出了注释。
     */
    @SuppressWarnings("serial")
    static final class ForEachKeyTask<K,V>
        extends BulkTask<K,V,Void> {
        final Consumer<? super K> action;
        ForEachKeyTask
            (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
             Consumer<? super K> action) {
            super(p, b, i, f, t);
            this.action = action;
        }
        public final void compute() {
            final Consumer<? super K> action;
            if ((action = this.action) != null) {
                // 如果预计的批量值还没用完，并且元素数量可以再拆，则拆子任务，一直拆到预估的批量值用完或者元素数量不能再拆为止。
                for (int i = baseIndex, f, h; batch > 0 &&
                         (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    new ForEachKeyTask<K,V>
                        (this, batch >>>= 1, baseLimit = h, f, tab,
                         action).fork();
                }
                // 完成自己这里的子任务，注意当前迭代器的元素数量已经在刚才的循环中剪出去了一些
                for (Node<K,V> p; (p = advance()) != null;)
                    action.accept(p.key);
                propagateCompletion(); // 尝试完成整个任务
            }
        }
    }

    @SuppressWarnings("serial")
    static final class ForEachValueTask<K,V>
        extends BulkTask<K,V,Void> {
        final Consumer<? super V> action;
        ForEachValueTask
            (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
             Consumer<? super V> action) {
            super(p, b, i, f, t);
            this.action = action;
        }
        public final void compute() {
            final Consumer<? super V> action;
            if ((action = this.action) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                         (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    new ForEachValueTask<K,V>
                        (this, batch >>>= 1, baseLimit = h, f, tab,
                         action).fork();
                }
                for (Node<K,V> p; (p = advance()) != null;)
                    action.accept(p.val);
                propagateCompletion();
            }
        }
    }

    @SuppressWarnings("serial")
    static final class ForEachEntryTask<K,V>
        extends BulkTask<K,V,Void> {
        final Consumer<? super Entry<K,V>> action;
        ForEachEntryTask
            (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
             Consumer<? super Entry<K,V>> action) {
            super(p, b, i, f, t);
            this.action = action;
        }
        public final void compute() {
            final Consumer<? super Entry<K,V>> action;
            if ((action = this.action) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                         (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    new ForEachEntryTask<K,V>
                        (this, batch >>>= 1, baseLimit = h, f, tab,
                         action).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; )
                    action.accept(p);
                propagateCompletion();
            }
        }
    }

    @SuppressWarnings("serial")
    static final class ForEachMappingTask<K,V>
        extends BulkTask<K,V,Void> {
        final BiConsumer<? super K, ? super V> action;
        ForEachMappingTask
            (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
             BiConsumer<? super K,? super V> action) {
            super(p, b, i, f, t);
            this.action = action;
        }
        public final void compute() {
            final BiConsumer<? super K, ? super V> action;
            if ((action = this.action) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                         (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    new ForEachMappingTask<K,V>
                        (this, batch >>>= 1, baseLimit = h, f, tab,
                         action).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; )
                    action.accept(p.key, p.val);
                propagateCompletion();
            }
        }
    }

    @SuppressWarnings("serial")
    static final class ForEachTransformedKeyTask<K,V,U>
        extends BulkTask<K,V,Void> {
        final Function<? super K, ? extends U> transformer;
        final Consumer<? super U> action;
        ForEachTransformedKeyTask
            (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
             Function<? super K, ? extends U> transformer, Consumer<? super U> action) {
            super(p, b, i, f, t);
            this.transformer = transformer; this.action = action;
        }
        public final void compute() {
            final Function<? super K, ? extends U> transformer;
            final Consumer<? super U> action;
            if ((transformer = this.transformer) != null &&
                (action = this.action) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                         (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    new ForEachTransformedKeyTask<K,V,U>
                        (this, batch >>>= 1, baseLimit = h, f, tab,
                         transformer, action).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; ) {
                    U u;
                    if ((u = transformer.apply(p.key)) != null)
                        action.accept(u);
                }
                propagateCompletion();
            }
        }
    }

    @SuppressWarnings("serial")
    static final class ForEachTransformedValueTask<K,V,U>
        extends BulkTask<K,V,Void> {
        final Function<? super V, ? extends U> transformer;
        final Consumer<? super U> action;
        ForEachTransformedValueTask
            (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
             Function<? super V, ? extends U> transformer, Consumer<? super U> action) {
            super(p, b, i, f, t);
            this.transformer = transformer; this.action = action;
        }
        public final void compute() {
            final Function<? super V, ? extends U> transformer;
            final Consumer<? super U> action;
            if ((transformer = this.transformer) != null &&
                (action = this.action) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                         (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    new ForEachTransformedValueTask<K,V,U>
                        (this, batch >>>= 1, baseLimit = h, f, tab,
                         transformer, action).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; ) {
                    U u;
                    if ((u = transformer.apply(p.val)) != null)
                        action.accept(u);
                }
                propagateCompletion();
            }
        }
    }

    @SuppressWarnings("serial")
    static final class ForEachTransformedEntryTask<K,V,U>
        extends BulkTask<K,V,Void> {
        final Function<Map.Entry<K,V>, ? extends U> transformer;
        final Consumer<? super U> action;
        ForEachTransformedEntryTask
            (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
             Function<Map.Entry<K,V>, ? extends U> transformer, Consumer<? super U> action) {
            super(p, b, i, f, t);
            this.transformer = transformer; this.action = action;
        }
        public final void compute() {
            final Function<Map.Entry<K,V>, ? extends U> transformer;
            final Consumer<? super U> action;
            if ((transformer = this.transformer) != null &&
                (action = this.action) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                         (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    new ForEachTransformedEntryTask<K,V,U>
                        (this, batch >>>= 1, baseLimit = h, f, tab,
                         transformer, action).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; ) {
                    U u;
                    if ((u = transformer.apply(p)) != null)
                        action.accept(u);
                }
                propagateCompletion();
            }
        }
    }

    @SuppressWarnings("serial")
    static final class ForEachTransformedMappingTask<K,V,U>
        extends BulkTask<K,V,Void> {
        final BiFunction<? super K, ? super V, ? extends U> transformer;
        final Consumer<? super U> action;
        ForEachTransformedMappingTask
            (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
             BiFunction<? super K, ? super V, ? extends U> transformer,
             Consumer<? super U> action) {
            super(p, b, i, f, t);
            this.transformer = transformer; this.action = action;
        }
        public final void compute() {
            final BiFunction<? super K, ? super V, ? extends U> transformer;
            final Consumer<? super U> action;
            if ((transformer = this.transformer) != null &&
                (action = this.action) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                         (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    new ForEachTransformedMappingTask<K,V,U>
                        (this, batch >>>= 1, baseLimit = h, f, tab,
                         transformer, action).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; ) {
                    U u;
                    if ((u = transformer.apply(p.key, p.val)) != null)
                        action.accept(u);
                }
                propagateCompletion();
            }
        }
    }

    @SuppressWarnings("serial")
    static final class SearchKeysTask<K,V,U>
        extends BulkTask<K,V,U> {
        final Function<? super K, ? extends U> searchFunction;
        final AtomicReference<U> result;
        SearchKeysTask
            (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
             Function<? super K, ? extends U> searchFunction,
             AtomicReference<U> result) {
            super(p, b, i, f, t);
            this.searchFunction = searchFunction; this.result = result;
        }
        public final U getRawResult() { return result.get(); }
        public final void compute() {
            final Function<? super K, ? extends U> searchFunction;
            final AtomicReference<U> result;
            if ((searchFunction = this.searchFunction) != null &&
                (result = this.result) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                         (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    if (result.get() != null)
                        return;
                    addToPendingCount(1);
                    new SearchKeysTask<K,V,U>
                        (this, batch >>>= 1, baseLimit = h, f, tab,
                         searchFunction, result).fork();
                }
                while (result.get() == null) {
                    U u;
                    Node<K,V> p;
                    if ((p = advance()) == null) {
                        propagateCompletion();
                        break;
                    }
                    if ((u = searchFunction.apply(p.key)) != null) {
                        if (result.compareAndSet(null, u))
                            quietlyCompleteRoot();
                        break;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class SearchValuesTask<K,V,U>
        extends BulkTask<K,V,U> {
        final Function<? super V, ? extends U> searchFunction;
        final AtomicReference<U> result;
        SearchValuesTask
            (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
             Function<? super V, ? extends U> searchFunction,
             AtomicReference<U> result) {
            super(p, b, i, f, t);
            this.searchFunction = searchFunction; this.result = result;
        }
        public final U getRawResult() { return result.get(); }
        public final void compute() {
            final Function<? super V, ? extends U> searchFunction;
            final AtomicReference<U> result;
            if ((searchFunction = this.searchFunction) != null &&
                (result = this.result) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                         (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    if (result.get() != null)
                        return;
                    addToPendingCount(1);
                    new SearchValuesTask<K,V,U>
                        (this, batch >>>= 1, baseLimit = h, f, tab,
                         searchFunction, result).fork();
                }
                while (result.get() == null) {
                    U u;
                    Node<K,V> p;
                    if ((p = advance()) == null) {
                        propagateCompletion();
                        break;
                    }
                    if ((u = searchFunction.apply(p.val)) != null) {
                        if (result.compareAndSet(null, u))
                            quietlyCompleteRoot();
                        break;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class SearchEntriesTask<K,V,U>
        extends BulkTask<K,V,U> {
        final Function<Entry<K,V>, ? extends U> searchFunction;
        final AtomicReference<U> result;
        SearchEntriesTask
            (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
             Function<Entry<K,V>, ? extends U> searchFunction,
             AtomicReference<U> result) {
            super(p, b, i, f, t);
            this.searchFunction = searchFunction; this.result = result;
        }
        public final U getRawResult() { return result.get(); }
        public final void compute() {
            final Function<Entry<K,V>, ? extends U> searchFunction;
            final AtomicReference<U> result;
            if ((searchFunction = this.searchFunction) != null &&
                (result = this.result) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                         (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    if (result.get() != null)
                        return;
                    addToPendingCount(1);
                    new SearchEntriesTask<K,V,U>
                        (this, batch >>>= 1, baseLimit = h, f, tab,
                         searchFunction, result).fork();
                }
                while (result.get() == null) {
                    U u;
                    Node<K,V> p;
                    if ((p = advance()) == null) {
                        propagateCompletion();
                        break;
                    }
                    if ((u = searchFunction.apply(p)) != null) {
                        if (result.compareAndSet(null, u))
                            quietlyCompleteRoot();
                        return;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class SearchMappingsTask<K,V,U>
        extends BulkTask<K,V,U> {
        final BiFunction<? super K, ? super V, ? extends U> searchFunction;
        final AtomicReference<U> result;
        SearchMappingsTask
            (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
             BiFunction<? super K, ? super V, ? extends U> searchFunction,
             AtomicReference<U> result) {
            super(p, b, i, f, t);
            this.searchFunction = searchFunction; this.result = result;
        }
        public final U getRawResult() { return result.get(); }
        public final void compute() {
            final BiFunction<? super K, ? super V, ? extends U> searchFunction;
            final AtomicReference<U> result;
            if ((searchFunction = this.searchFunction) != null &&
                (result = this.result) != null) { // 如果还没有搜索到结果才继续搜索
                for (int i = baseIndex, f, h; batch > 0 &&
                         (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    if (result.get() != null)
                        return;
                    addToPendingCount(1);
                    new SearchMappingsTask<K,V,U>
                        (this, batch >>>= 1, baseLimit = h, f, tab,
                         searchFunction, result).fork();
                }
                while (result.get() == null) {
                    U u;
                    Node<K,V> p;
                    if ((p = advance()) == null) {
                        propagateCompletion();
                        break;
                    }
                    if ((u = searchFunction.apply(p.key, p.val)) != null) { // 如果搜索到了
                        if (result.compareAndSet(null, u)) // 把结果放入result，阻止其他子任务继续进行
                            quietlyCompleteRoot(); // 然后执行这个方法把整个任务都结束掉
                        break;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class ReduceKeysTask<K,V>
        extends BulkTask<K,V,K> {
        final BiFunction<? super K, ? super K, ? extends K> reducer;
        K result;
        ReduceKeysTask<K,V> rights, nextRight;
        ReduceKeysTask
            (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
             ReduceKeysTask<K,V> nextRight,
             BiFunction<? super K, ? super K, ? extends K> reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.reducer = reducer;
        }
        public final K getRawResult() { return result; }
        public final void compute() {
            final BiFunction<? super K, ? super K, ? extends K> reducer;
            if ((reducer = this.reducer) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                         (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new ReduceKeysTask<K,V>
                     (this, batch >>>= 1, baseLimit = h, f, tab,
                      rights, reducer)).fork();
                }
                K r = null;
                for (Node<K,V> p; (p = advance()) != null; ) {
                    K u = p.key;
                    r = (r == null) ? u : u == null ? r : reducer.apply(r, u);
                }
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    ReduceKeysTask<K,V>
                        t = (ReduceKeysTask<K,V>)c,
                        s = t.rights;
                    while (s != null) {
                        K tr, sr;
                        if ((sr = s.result) != null)
                            t.result = (((tr = t.result) == null) ? sr :
                                        reducer.apply(tr, sr));
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class ReduceValuesTask<K,V>
        extends BulkTask<K,V,V> {
        final BiFunction<? super V, ? super V, ? extends V> reducer;
        V result;
        ReduceValuesTask<K,V> rights, nextRight;
        ReduceValuesTask
            (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
             ReduceValuesTask<K,V> nextRight,
             BiFunction<? super V, ? super V, ? extends V> reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.reducer = reducer;
        }
        public final V getRawResult() { return result; }
        public final void compute() {
            final BiFunction<? super V, ? super V, ? extends V> reducer;
            if ((reducer = this.reducer) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                         (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new ReduceValuesTask<K,V>
                     (this, batch >>>= 1, baseLimit = h, f, tab,
                      rights, reducer)).fork();
                }
                V r = null;
                for (Node<K,V> p; (p = advance()) != null; ) {
                    V v = p.val;
                    r = (r == null) ? v : reducer.apply(r, v);
                }
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    ReduceValuesTask<K,V>
                        t = (ReduceValuesTask<K,V>)c,
                        s = t.rights;
                    while (s != null) {
                        V tr, sr;
                        if ((sr = s.result) != null)
                            t.result = (((tr = t.result) == null) ? sr :
                                        reducer.apply(tr, sr));
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class ReduceEntriesTask<K,V>
        extends BulkTask<K,V,Map.Entry<K,V>> {
        final BiFunction<Map.Entry<K,V>, Map.Entry<K,V>, ? extends Map.Entry<K,V>> reducer;
        Map.Entry<K,V> result;
        ReduceEntriesTask<K,V> rights, nextRight;
        ReduceEntriesTask
            (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
             ReduceEntriesTask<K,V> nextRight,
             BiFunction<Entry<K,V>, Map.Entry<K,V>, ? extends Map.Entry<K,V>> reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.reducer = reducer;
        }
        public final Map.Entry<K,V> getRawResult() { return result; }
        public final void compute() {
            final BiFunction<Map.Entry<K,V>, Map.Entry<K,V>, ? extends Map.Entry<K,V>> reducer;
            if ((reducer = this.reducer) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                         (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new ReduceEntriesTask<K,V>
                     (this, batch >>>= 1, baseLimit = h, f, tab,
                      rights, reducer)).fork();
                }
                Map.Entry<K,V> r = null;
                for (Node<K,V> p; (p = advance()) != null; )
                    r = (r == null) ? p : reducer.apply(r, p);
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    ReduceEntriesTask<K,V>
                        t = (ReduceEntriesTask<K,V>)c,
                        s = t.rights;
                    while (s != null) {
                        Map.Entry<K,V> tr, sr;
                        if ((sr = s.result) != null)
                            t.result = (((tr = t.result) == null) ? sr :
                                        reducer.apply(tr, sr));
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class MapReduceKeysTask<K,V,U>
        extends BulkTask<K,V,U> {
        final Function<? super K, ? extends U> transformer;
        final BiFunction<? super U, ? super U, ? extends U> reducer;
        U result;
        MapReduceKeysTask<K,V,U> rights, nextRight;
        MapReduceKeysTask
            (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
             MapReduceKeysTask<K,V,U> nextRight,
             Function<? super K, ? extends U> transformer,
             BiFunction<? super U, ? super U, ? extends U> reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.transformer = transformer;
            this.reducer = reducer;
        }
        public final U getRawResult() { return result; }
        public final void compute() {
            final Function<? super K, ? extends U> transformer;
            final BiFunction<? super U, ? super U, ? extends U> reducer;
            if ((transformer = this.transformer) != null &&
                (reducer = this.reducer) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                         (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new MapReduceKeysTask<K,V,U>
                     (this, batch >>>= 1, baseLimit = h, f, tab,
                      rights, transformer, reducer)).fork();
                }
                U r = null;
                for (Node<K,V> p; (p = advance()) != null; ) {
                    U u;
                    if ((u = transformer.apply(p.key)) != null)
                        r = (r == null) ? u : reducer.apply(r, u);
                }
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceKeysTask<K,V,U>
                        t = (MapReduceKeysTask<K,V,U>)c,
                        s = t.rights;
                    while (s != null) {
                        U tr, sr;
                        if ((sr = s.result) != null)
                            t.result = (((tr = t.result) == null) ? sr :
                                        reducer.apply(tr, sr));
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class MapReduceValuesTask<K,V,U>
        extends BulkTask<K,V,U> {
        final Function<? super V, ? extends U> transformer;
        final BiFunction<? super U, ? super U, ? extends U> reducer;
        U result;
        MapReduceValuesTask<K,V,U> rights, nextRight;
        MapReduceValuesTask
            (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
             MapReduceValuesTask<K,V,U> nextRight,
             Function<? super V, ? extends U> transformer,
             BiFunction<? super U, ? super U, ? extends U> reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.transformer = transformer;
            this.reducer = reducer;
        }
        public final U getRawResult() { return result; }
        public final void compute() {
            final Function<? super V, ? extends U> transformer;
            final BiFunction<? super U, ? super U, ? extends U> reducer;
            if ((transformer = this.transformer) != null &&
                (reducer = this.reducer) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                         (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new MapReduceValuesTask<K,V,U>
                     (this, batch >>>= 1, baseLimit = h, f, tab,
                      rights, transformer, reducer)).fork();
                }
                U r = null;
                for (Node<K,V> p; (p = advance()) != null; ) {
                    U u;
                    if ((u = transformer.apply(p.val)) != null)
                        r = (r == null) ? u : reducer.apply(r, u);
                }
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceValuesTask<K,V,U>
                        t = (MapReduceValuesTask<K,V,U>)c,
                        s = t.rights;
                    while (s != null) {
                        U tr, sr;
                        if ((sr = s.result) != null)
                            t.result = (((tr = t.result) == null) ? sr :
                                        reducer.apply(tr, sr));
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class MapReduceEntriesTask<K,V,U>
        extends BulkTask<K,V,U> {
        final Function<Map.Entry<K,V>, ? extends U> transformer;
        final BiFunction<? super U, ? super U, ? extends U> reducer;
        U result;
        MapReduceEntriesTask<K,V,U> rights, nextRight;
        MapReduceEntriesTask
            (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
             MapReduceEntriesTask<K,V,U> nextRight,
             Function<Map.Entry<K,V>, ? extends U> transformer,
             BiFunction<? super U, ? super U, ? extends U> reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.transformer = transformer;
            this.reducer = reducer;
        }
        public final U getRawResult() { return result; }
        public final void compute() {
            final Function<Map.Entry<K,V>, ? extends U> transformer;
            final BiFunction<? super U, ? super U, ? extends U> reducer;
            if ((transformer = this.transformer) != null &&
                (reducer = this.reducer) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                         (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new MapReduceEntriesTask<K,V,U>
                     (this, batch >>>= 1, baseLimit = h, f, tab,
                      rights, transformer, reducer)).fork();
                }
                U r = null;
                for (Node<K,V> p; (p = advance()) != null; ) {
                    U u;
                    if ((u = transformer.apply(p)) != null)
                        r = (r == null) ? u : reducer.apply(r, u);
                }
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceEntriesTask<K,V,U>
                        t = (MapReduceEntriesTask<K,V,U>)c,
                        s = t.rights;
                    while (s != null) {
                        U tr, sr;
                        if ((sr = s.result) != null)
                            t.result = (((tr = t.result) == null) ? sr :
                                        reducer.apply(tr, sr));
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class MapReduceMappingsTask<K,V,U>
        extends BulkTask<K,V,U> {
        final BiFunction<? super K, ? super V, ? extends U> transformer;
        final BiFunction<? super U, ? super U, ? extends U> reducer;
        U result;
        MapReduceMappingsTask<K,V,U> rights, nextRight;
        MapReduceMappingsTask
            (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
             MapReduceMappingsTask<K,V,U> nextRight,
             BiFunction<? super K, ? super V, ? extends U> transformer,
             BiFunction<? super U, ? super U, ? extends U> reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.transformer = transformer;
            this.reducer = reducer;
        }
        public final U getRawResult() { return result; }
        public final void compute() {
            final BiFunction<? super K, ? super V, ? extends U> transformer;
            final BiFunction<? super U, ? super U, ? extends U> reducer;
            if ((transformer = this.transformer) != null &&
                (reducer = this.reducer) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                         (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new MapReduceMappingsTask<K,V,U>
                     (this, batch >>>= 1, baseLimit = h, f, tab,
                      rights, transformer, reducer)).fork(); // 注意这里用nextRight指针把子任务串起来了，它们之间是completer关系
                }
                U r = null;
                for (Node<K,V> p; (p = advance()) != null; ) { // 将本子任务中的key和value依次计算得到u
                    U u;
                    if ((u = transformer.apply(p.key, p.val)) != null)
                        r = (r == null) ? u : reducer.apply(r, u); // 将每次得到的u用聚合方法聚合到result中
                }
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) { // 沿着completer把所有已完成的子任务结果聚合
                    @SuppressWarnings("unchecked")
                    MapReduceMappingsTask<K,V,U>
                        t = (MapReduceMappingsTask<K,V,U>)c,
                        s = t.rights;
                    while (s != null) {
                        U tr, sr;
                        if ((sr = s.result) != null)
                            t.result = (((tr = t.result) == null) ? sr :
                                        reducer.apply(tr, sr));
                        s = t.rights = s.nextRight; // 沿着nextRight指针依次聚合result
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class MapReduceKeysToDoubleTask<K,V>
        extends BulkTask<K,V,Double> {
        final ToDoubleFunction<? super K> transformer;
        final DoubleBinaryOperator reducer;
        final double basis;
        double result;
        MapReduceKeysToDoubleTask<K,V> rights, nextRight;
        MapReduceKeysToDoubleTask
            (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
             MapReduceKeysToDoubleTask<K,V> nextRight,
             ToDoubleFunction<? super K> transformer,
             double basis,
             DoubleBinaryOperator reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis; this.reducer = reducer;
        }
        public final Double getRawResult() { return result; }
        public final void compute() {
            final ToDoubleFunction<? super K> transformer;
            final DoubleBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                (reducer = this.reducer) != null) {
                double r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                         (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new MapReduceKeysToDoubleTask<K,V>
                     (this, batch >>>= 1, baseLimit = h, f, tab,
                      rights, transformer, r, reducer)).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; )
                    r = reducer.applyAsDouble(r, transformer.applyAsDouble(p.key));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceKeysToDoubleTask<K,V>
                        t = (MapReduceKeysToDoubleTask<K,V>)c,
                        s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsDouble(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class MapReduceValuesToDoubleTask<K,V>
        extends BulkTask<K,V,Double> {
        final ToDoubleFunction<? super V> transformer;
        final DoubleBinaryOperator reducer;
        final double basis;
        double result;
        MapReduceValuesToDoubleTask<K,V> rights, nextRight;
        MapReduceValuesToDoubleTask
            (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
             MapReduceValuesToDoubleTask<K,V> nextRight,
             ToDoubleFunction<? super V> transformer,
             double basis,
             DoubleBinaryOperator reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis; this.reducer = reducer;
        }
        public final Double getRawResult() { return result; }
        public final void compute() {
            final ToDoubleFunction<? super V> transformer;
            final DoubleBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                (reducer = this.reducer) != null) {
                double r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                         (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new MapReduceValuesToDoubleTask<K,V>
                     (this, batch >>>= 1, baseLimit = h, f, tab,
                      rights, transformer, r, reducer)).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; )
                    r = reducer.applyAsDouble(r, transformer.applyAsDouble(p.val));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceValuesToDoubleTask<K,V>
                        t = (MapReduceValuesToDoubleTask<K,V>)c,
                        s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsDouble(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class MapReduceEntriesToDoubleTask<K,V>
        extends BulkTask<K,V,Double> {
        final ToDoubleFunction<Map.Entry<K,V>> transformer;
        final DoubleBinaryOperator reducer;
        final double basis;
        double result;
        MapReduceEntriesToDoubleTask<K,V> rights, nextRight;
        MapReduceEntriesToDoubleTask
            (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
             MapReduceEntriesToDoubleTask<K,V> nextRight,
             ToDoubleFunction<Map.Entry<K,V>> transformer,
             double basis,
             DoubleBinaryOperator reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis; this.reducer = reducer;
        }
        public final Double getRawResult() { return result; }
        public final void compute() {
            final ToDoubleFunction<Map.Entry<K,V>> transformer;
            final DoubleBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                (reducer = this.reducer) != null) {
                double r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                         (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new MapReduceEntriesToDoubleTask<K,V>
                     (this, batch >>>= 1, baseLimit = h, f, tab,
                      rights, transformer, r, reducer)).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; )
                    r = reducer.applyAsDouble(r, transformer.applyAsDouble(p));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceEntriesToDoubleTask<K,V>
                        t = (MapReduceEntriesToDoubleTask<K,V>)c,
                        s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsDouble(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class MapReduceMappingsToDoubleTask<K,V>
        extends BulkTask<K,V,Double> {
        final ToDoubleBiFunction<? super K, ? super V> transformer;
        final DoubleBinaryOperator reducer;
        final double basis;
        double result;
        MapReduceMappingsToDoubleTask<K,V> rights, nextRight;
        MapReduceMappingsToDoubleTask
            (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
             MapReduceMappingsToDoubleTask<K,V> nextRight,
             ToDoubleBiFunction<? super K, ? super V> transformer,
             double basis,
             DoubleBinaryOperator reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis; this.reducer = reducer;
        }
        public final Double getRawResult() { return result; }
        public final void compute() {
            final ToDoubleBiFunction<? super K, ? super V> transformer;
            final DoubleBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                (reducer = this.reducer) != null) {
                double r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                         (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new MapReduceMappingsToDoubleTask<K,V>
                     (this, batch >>>= 1, baseLimit = h, f, tab,
                      rights, transformer, r, reducer)).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; )
                    r = reducer.applyAsDouble(r, transformer.applyAsDouble(p.key, p.val));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceMappingsToDoubleTask<K,V>
                        t = (MapReduceMappingsToDoubleTask<K,V>)c,
                        s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsDouble(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class MapReduceKeysToLongTask<K,V>
        extends BulkTask<K,V,Long> {
        final ToLongFunction<? super K> transformer;
        final LongBinaryOperator reducer;
        final long basis;
        long result;
        MapReduceKeysToLongTask<K,V> rights, nextRight;
        MapReduceKeysToLongTask
            (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
             MapReduceKeysToLongTask<K,V> nextRight,
             ToLongFunction<? super K> transformer,
             long basis,
             LongBinaryOperator reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis; this.reducer = reducer;
        }
        public final Long getRawResult() { return result; }
        public final void compute() {
            final ToLongFunction<? super K> transformer;
            final LongBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                (reducer = this.reducer) != null) {
                long r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                         (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new MapReduceKeysToLongTask<K,V>
                     (this, batch >>>= 1, baseLimit = h, f, tab,
                      rights, transformer, r, reducer)).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; )
                    r = reducer.applyAsLong(r, transformer.applyAsLong(p.key));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceKeysToLongTask<K,V>
                        t = (MapReduceKeysToLongTask<K,V>)c,
                        s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsLong(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class MapReduceValuesToLongTask<K,V>
        extends BulkTask<K,V,Long> {
        final ToLongFunction<? super V> transformer;
        final LongBinaryOperator reducer;
        final long basis;
        long result;
        MapReduceValuesToLongTask<K,V> rights, nextRight;
        MapReduceValuesToLongTask
            (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
             MapReduceValuesToLongTask<K,V> nextRight,
             ToLongFunction<? super V> transformer,
             long basis,
             LongBinaryOperator reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis; this.reducer = reducer;
        }
        public final Long getRawResult() { return result; }
        public final void compute() {
            final ToLongFunction<? super V> transformer;
            final LongBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                (reducer = this.reducer) != null) {
                long r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                         (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new MapReduceValuesToLongTask<K,V>
                     (this, batch >>>= 1, baseLimit = h, f, tab,
                      rights, transformer, r, reducer)).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; )
                    r = reducer.applyAsLong(r, transformer.applyAsLong(p.val));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceValuesToLongTask<K,V>
                        t = (MapReduceValuesToLongTask<K,V>)c,
                        s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsLong(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class MapReduceEntriesToLongTask<K,V>
        extends BulkTask<K,V,Long> {
        final ToLongFunction<Map.Entry<K,V>> transformer;
        final LongBinaryOperator reducer;
        final long basis;
        long result;
        MapReduceEntriesToLongTask<K,V> rights, nextRight;
        MapReduceEntriesToLongTask
            (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
             MapReduceEntriesToLongTask<K,V> nextRight,
             ToLongFunction<Map.Entry<K,V>> transformer,
             long basis,
             LongBinaryOperator reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis; this.reducer = reducer;
        }
        public final Long getRawResult() { return result; }
        public final void compute() {
            final ToLongFunction<Map.Entry<K,V>> transformer;
            final LongBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                (reducer = this.reducer) != null) {
                long r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                         (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new MapReduceEntriesToLongTask<K,V>
                     (this, batch >>>= 1, baseLimit = h, f, tab,
                      rights, transformer, r, reducer)).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; )
                    r = reducer.applyAsLong(r, transformer.applyAsLong(p));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceEntriesToLongTask<K,V>
                        t = (MapReduceEntriesToLongTask<K,V>)c,
                        s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsLong(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class MapReduceMappingsToLongTask<K,V>
        extends BulkTask<K,V,Long> {
        final ToLongBiFunction<? super K, ? super V> transformer;
        final LongBinaryOperator reducer;
        final long basis;
        long result;
        MapReduceMappingsToLongTask<K,V> rights, nextRight;
        MapReduceMappingsToLongTask
            (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
             MapReduceMappingsToLongTask<K,V> nextRight,
             ToLongBiFunction<? super K, ? super V> transformer,
             long basis,
             LongBinaryOperator reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis; this.reducer = reducer;
        }
        public final Long getRawResult() { return result; }
        public final void compute() {
            final ToLongBiFunction<? super K, ? super V> transformer;
            final LongBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                (reducer = this.reducer) != null) {
                long r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                         (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new MapReduceMappingsToLongTask<K,V>
                     (this, batch >>>= 1, baseLimit = h, f, tab,
                      rights, transformer, r, reducer)).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; )
                    r = reducer.applyAsLong(r, transformer.applyAsLong(p.key, p.val));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceMappingsToLongTask<K,V>
                        t = (MapReduceMappingsToLongTask<K,V>)c,
                        s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsLong(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class MapReduceKeysToIntTask<K,V>
        extends BulkTask<K,V,Integer> {
        final ToIntFunction<? super K> transformer;
        final IntBinaryOperator reducer;
        final int basis;
        int result;
        MapReduceKeysToIntTask<K,V> rights, nextRight;
        MapReduceKeysToIntTask
            (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
             MapReduceKeysToIntTask<K,V> nextRight,
             ToIntFunction<? super K> transformer,
             int basis,
             IntBinaryOperator reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis; this.reducer = reducer;
        }
        public final Integer getRawResult() { return result; }
        public final void compute() {
            final ToIntFunction<? super K> transformer;
            final IntBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                (reducer = this.reducer) != null) {
                int r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                         (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new MapReduceKeysToIntTask<K,V>
                     (this, batch >>>= 1, baseLimit = h, f, tab,
                      rights, transformer, r, reducer)).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; )
                    r = reducer.applyAsInt(r, transformer.applyAsInt(p.key));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceKeysToIntTask<K,V>
                        t = (MapReduceKeysToIntTask<K,V>)c,
                        s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsInt(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class MapReduceValuesToIntTask<K,V>
        extends BulkTask<K,V,Integer> {
        final ToIntFunction<? super V> transformer;
        final IntBinaryOperator reducer;
        final int basis;
        int result;
        MapReduceValuesToIntTask<K,V> rights, nextRight;
        MapReduceValuesToIntTask
            (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
             MapReduceValuesToIntTask<K,V> nextRight,
             ToIntFunction<? super V> transformer,
             int basis,
             IntBinaryOperator reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis; this.reducer = reducer;
        }
        public final Integer getRawResult() { return result; }
        public final void compute() {
            final ToIntFunction<? super V> transformer;
            final IntBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                (reducer = this.reducer) != null) {
                int r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                         (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new MapReduceValuesToIntTask<K,V>
                     (this, batch >>>= 1, baseLimit = h, f, tab,
                      rights, transformer, r, reducer)).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; )
                    r = reducer.applyAsInt(r, transformer.applyAsInt(p.val));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceValuesToIntTask<K,V>
                        t = (MapReduceValuesToIntTask<K,V>)c,
                        s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsInt(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class MapReduceEntriesToIntTask<K,V>
        extends BulkTask<K,V,Integer> {
        final ToIntFunction<Map.Entry<K,V>> transformer;
        final IntBinaryOperator reducer;
        final int basis;
        int result;
        MapReduceEntriesToIntTask<K,V> rights, nextRight;
        MapReduceEntriesToIntTask
            (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
             MapReduceEntriesToIntTask<K,V> nextRight,
             ToIntFunction<Map.Entry<K,V>> transformer,
             int basis,
             IntBinaryOperator reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis; this.reducer = reducer;
        }
        public final Integer getRawResult() { return result; }
        public final void compute() {
            final ToIntFunction<Map.Entry<K,V>> transformer;
            final IntBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                (reducer = this.reducer) != null) {
                int r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                         (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new MapReduceEntriesToIntTask<K,V>
                     (this, batch >>>= 1, baseLimit = h, f, tab,
                      rights, transformer, r, reducer)).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; )
                    r = reducer.applyAsInt(r, transformer.applyAsInt(p));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceEntriesToIntTask<K,V>
                        t = (MapReduceEntriesToIntTask<K,V>)c,
                        s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsInt(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class MapReduceMappingsToIntTask<K,V>
        extends BulkTask<K,V,Integer> {
        final ToIntBiFunction<? super K, ? super V> transformer;
        final IntBinaryOperator reducer;
        final int basis;
        int result;
        MapReduceMappingsToIntTask<K,V> rights, nextRight;
        MapReduceMappingsToIntTask
            (BulkTask<K,V,?> p, int b, int i, int f, Node<K,V>[] t,
             MapReduceMappingsToIntTask<K,V> nextRight,
             ToIntBiFunction<? super K, ? super V> transformer,
             int basis,
             IntBinaryOperator reducer) {
            super(p, b, i, f, t); this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis; this.reducer = reducer;
        }
        public final Integer getRawResult() { return result; }
        public final void compute() {
            final ToIntBiFunction<? super K, ? super V> transformer;
            final IntBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                (reducer = this.reducer) != null) {
                int r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                         (h = ((f = baseLimit) + i) >>> 1) > i;) {
                    addToPendingCount(1);
                    (rights = new MapReduceMappingsToIntTask<K,V>
                     (this, batch >>>= 1, baseLimit = h, f, tab,
                      rights, transformer, r, reducer)).fork();
                }
                for (Node<K,V> p; (p = advance()) != null; )
                    r = reducer.applyAsInt(r, transformer.applyAsInt(p.key, p.val));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceMappingsToIntTask<K,V>
                        t = (MapReduceMappingsToIntTask<K,V>)c,
                        s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsInt(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe U;
    private static final long SIZECTL;
    private static final long TRANSFERINDEX;
    private static final long BASECOUNT;
    private static final long CELLSBUSY;
    private static final long CELLVALUE;
    private static final long ABASE;
    private static final int ASHIFT;

    static {
        try {
            U = sun.misc.Unsafe.getUnsafe();
            Class<?> k = ConcurrentHashMap.class;
            SIZECTL = U.objectFieldOffset
                (k.getDeclaredField("sizeCtl"));
            TRANSFERINDEX = U.objectFieldOffset
                (k.getDeclaredField("transferIndex"));
            BASECOUNT = U.objectFieldOffset
                (k.getDeclaredField("baseCount"));
            CELLSBUSY = U.objectFieldOffset
                (k.getDeclaredField("cellsBusy"));
            Class<?> ck = CounterCell.class;
            CELLVALUE = U.objectFieldOffset
                (ck.getDeclaredField("value"));
            Class<?> ak = Node[].class;
            ABASE = U.arrayBaseOffset(ak); // 获取Node数组在内存中的起始位置的偏移量
            int scale = U.arrayIndexScale(ak); // 获取Node数组中存放的元素的位数，例如Integer是4位，二进制100
            if ((scale & (scale - 1)) != 0)
                throw new Error("data type scale not a power of two");
            ASHIFT = 31 - Integer.numberOfLeadingZeros(scale); // 因为共32位，100前面有29个0，ASHIFT = 31 - 29 = 2
        } catch (Exception e) {
            throw new Error(e);
        }
    }
}
