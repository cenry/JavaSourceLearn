/*
 * Copyright (c) 1997, 2017, Oracle and/or its affiliates. All rights reserved.
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

package java.util;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.Serializable;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import sun.misc.SharedSecrets;

/**
 * @param <K> map中维护的key的类型
 * @param <V> key对应的value的类型
 *
 * @author  Doug Lea
 * @author  Josh Bloch
 * @author  Arthur van Hoff
 * @author  Neal Gafter
 * @see     Object#hashCode()
 * @see     Collection
 * @see     Map
 * @see     TreeMap
 * @see     Hashtable
 * @since   1.2
 */
public class HashMap<K,V> extends AbstractMap<K,V>
    implements Map<K,V>, Cloneable, Serializable {

    private static final long serialVersionUID = 362498820763181265L;

    /**
     * 默认的初始容量——必须为2的倍数。
     */
    static final int DEFAULT_INITIAL_CAPACITY = 1 << 4; // aka 16

    /**
     * 最大容量，如果使用者通过构造器指定的容量太大，就会使用这个值，它必须是2的倍数且小于等于2的30次方。
     */
    static final int MAXIMUM_CAPACITY = 1 << 30;

    /**
     * 负载因子，如果构造器中没指定，就使用这个值。
     */
    static final float DEFAULT_LOAD_FACTOR = 0.75f;

    /**
     * 容器由链表转为树结构的临界值，默认的，如果容器中现在有8个元素，下一个放入该容器的元素将导致容器结构由链表转为树。
     * 为了与元素数量减少时容器结构转回链表的临界值契合，这个值必须大于2并且至少是8。
     */
    static final int TREEIFY_THRESHOLD = 8;

    /**
     * 元素数量减少时容器结构转回链表的临界值，默认的，如果容器现在是树结构，且刚好有6个元素，再移除掉一个元素，这个容器将转回链表。
     * 这个值要小于TREEIFY_THRESHOLD，此外，为了避免链表和树结构的频繁转换，最大只能定为6。
     */
    static final int UNTREEIFY_THRESHOLD = 6;

    /**
     * table容量至少需要达到这个值，才会在单个容器元素过多时转红黑树，否则元素数量达到TREEIFY_THRESHOLD时首先考虑的是将整个table扩容。
     * 这个值至少是4 * TREEIFY_THRESHOLD，以避免扩容阈值和树结构化的门槛值发生冲突。
     */
    static final int MIN_TREEIFY_CAPACITY = 64;

    /**
     * 容器中基本的节点，HashMap中大多数成员都是它。
     * HashMap的内部类TreeNode和子类LinkedHashMap中会重写它以满足特定需求。
     * 是一个单向链表结构。
     */
    static class Node<K,V> implements Map.Entry<K,V> {
        final int hash;
        final K key;
        V value;
        Node<K,V> next;

        Node(int hash, K key, V value, Node<K,V> next) {
            this.hash = hash;
            this.key = key;
            this.value = value;
            this.next = next;
        }

        public final K getKey()        { return key; }
        public final V getValue()      { return value; }
        public final String toString() { return key + "=" + value; }

        // 注意这个方法的计算结果和类字段hash不一样
        public final int hashCode() {
            return Objects.hashCode(key) ^ Objects.hashCode(value);
        }

        public final V setValue(V newValue) {
            V oldValue = value;
            value = newValue;
            return oldValue;
        }

        public final boolean equals(Object o) {
            if (o == this)
                return true;
            if (o instanceof Map.Entry) {
                Map.Entry<?,?> e = (Map.Entry<?,?>)o;
                if (Objects.equals(key, e.getKey()) &&
                    Objects.equals(value, e.getValue()))
                    return true;
            }
            return false;
        }
    }

    /* ---------------- 静态的通用方法 -------------- */

    /**
     * 计算key的哈希值，并将结果的高16位与低16位进行进行异或操作。为什么这么做？
     * 因为一开始我们的HashMap初始容量只有16，不能直接拿这样算出来的哈希值来决定将元素放在哪个容器中，
     * 还需要一次(n - 1) & hash操作得到最终的哈希值，其中n是当前的总容量。
     * 假设容量是初始的16，演示一下某两个key的计算过程（其中无关的位用x省略）：
     *
     * 方法1：直接拿key.hashCode()的结果操作
     * hash1 = key1.hashCode()  xxxx xxxx xxxx 1111 xxxx xxxx xxxx 1111
     * hash2 = key2.hashCode()  xxxx xxxx xxxx 1100 xxxx xxxx xxxx 1111
     * 16 - 1                   0000 0000 0000 0000 0000 0000 0000 1111
     * (16 - 1) & hash1         0000 0000 0000 0000 0000 0000 0000 0000
     * (16 - 1) & hash2         0000 0000 0000 0000 0000 0000 0000 0000
     * 结果两个不同的key哈希值发生了碰撞
     *
     * 方法2：把高16位的信息混到低16位中
     * h1 = key1.hashCode()     xxxx xxxx xxxx 1111 xxxx xxxx xxxx 1111
     * h2 = key2.hashCode()     xxxx xxxx xxxx 1100 xxxx xxxx xxxx 1111
     * hash1 = h1 ^ (h1 >>> 16) xxxx xxxx xxxx 1111 xxxx xxxx xxxx 0000
     * hash2 = h2 ^ (h2 >>> 16) xxxx xxxx xxxx 1100 xxxx xxxx xxxx 0011
     * 16 - 1                   0000 0000 0000 0000 0000 0000 0000 1111
     * (16 - 1) & hash1         0000 0000 0000 0000 0000 0000 0000 0000
     * (16 - 1) & hash2         0000 0000 0000 0000 0000 0000 0000 0011
     * 它们的最终哈希值不一样了，这样就有效减少了一次哈希冲突
     *
     * 其实这种处理也可能会使原来不会发生哈希碰撞的key发生哈希碰撞。
     * 如果key2.hashCode()的低位的末4位是1100，它与高位的末4位1100异或之后变成了0000，和hash1的值一样了。
     *
     * 这里最终还是采用了这种方式，因为实验证明这种方式的确能减少哈希碰撞，虽然程度较小。
     *
     * 另外需要注意的一点，HashMap允许key为null，而且null的hash结果为0，也就是说它放在map的最前面。
     */
    static final int hash(Object key) {
        int h;
        return (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16);
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

    /**
     * 返回大于等于输入参数且最近的2的整数次幂的数。
     * cap          0010 1001 1000 1101
     * n = cap - 1  0010 1001 1000 1100
     * n >>> 1      0001 0100 1100 0110
     * |            0011 1101 1100 1110
     * n >>> 2      0000 1111 0111 0011
     * |            0011 1111 1111 1111
     * ...
     * 其实这么多次移位操作是为了把最高有效位后面的位都填充为1，由于到此为止已填充完毕，不再列举后面的移位操作。
     * 最后再+1得到2的整数次幂：
     * n + 1        0100 0000 0000 0000
     *
     * 为什么一开始要-1？因为如果输入的刚好是2的整数次幂：
     * cap      0010 0000
     * n = cap  0010 0000
     * ...
     * n        0011 1111
     * n + 1    0100 0000
     * 本应该返回它本身，最终却返回了大一倍的数字。
     *
     * 另外，如果cap是0，此方法计算结果n为-1，最终返回1
     */
    static final int tableSizeFor(int cap) {
        int n = cap - 1;
        n |= n >>> 1;
        n |= n >>> 2;
        n |= n >>> 4;
        n |= n >>> 8;
        n |= n >>> 16;
        return (n < 0) ? 1 : (n >= MAXIMUM_CAPACITY) ? MAXIMUM_CAPACITY : n + 1;
    }

    /* ---------------- 字段 -------------- */

    /**
     * 存放数据的表格，初次使用时初始化，在有必要时调整大小。长度总是2的整数次幂。
     * 长度允许为0，表示当前没有被使用。
     */
    transient Node<K,V>[] table;

    /**
     * entrySet()方法的缓存。注意keySet()和values()用的不是它。
     */
    transient Set<Map.Entry<K,V>> entrySet;

    /**
     * map中键-值映射关系的数量
     */
    transient int size;

    /**
     * HashMap的结构已被修改的次数。结构修改就是映射关系的数量发生改变，或内部结构发生改变（rehash）。
     * 这个字段使HashMap在以Collection形式进行迭代时遵循fail-fast机制。（参考ConcurrentModificationException）
     */
    transient int modCount;

    /**
     * 下一次扩容时的目标值（当前容量 * 负载因子）
     * 如果table数组还没被创建，那么这个字段的值就是指定的初始的数组容量，或者0——代表DEFAULT_INITIAL_CAPACITY
     *
     * @serial 该注解用于表示序列化的字段
     */
    int threshold;

    /**
     * 哈希表的负载因子
     *
     * @serial 该注解用于表示序列化的字段
     */
    final float loadFactor;

    /* ---------------- 公有方法 -------------- */

    /**
     * 构造一个空的HashMap并指定初始容量和负载因子
     * @param  initialCapacity 初始容量
     * @param  loadFactor      负载因子
     * @throws IllegalArgumentException 如果初始容量是负数或负载因子是非正数
     */
    public HashMap(int initialCapacity, float loadFactor) {
        if (initialCapacity < 0)
            throw new IllegalArgumentException("Illegal initial capacity: " +
                                               initialCapacity);
        if (initialCapacity > MAXIMUM_CAPACITY)
            initialCapacity = MAXIMUM_CAPACITY;
        if (loadFactor <= 0 || Float.isNaN(loadFactor))
            throw new IllegalArgumentException("Illegal load factor: " +
                                               loadFactor);
        this.loadFactor = loadFactor;
        this.threshold = tableSizeFor(initialCapacity);
    }

    /**
     * 构造一个空的HashMap并指定初始容量，负载因子使用默认的0.75
     * @param  initialCapacity 初始容量
     * @throws IllegalArgumentException 如果初始容量是负数
     */
    public HashMap(int initialCapacity) {
        this(initialCapacity, DEFAULT_LOAD_FACTOR);
    }

    /**
     * 构造一个空的HashMap，负载因子使用默认的0.75
     */
    public HashMap() {
        this.loadFactor = DEFAULT_LOAD_FACTOR; // 其他字段都使用初始值，例如容量是0
    }

    /**
     * 构造一个新的HashMap，它的初始成员和指定的Map中的成员一样。
     * 初始容量会被设定为足够存放初始元素的值，负载因子使用默认的0.75。
     *
     * @param   m 其元素将被放入到新的map中
     * @throws  NullPointerException 如果指定的map是null
     */
    public HashMap(Map<? extends K, ? extends V> m) {
        this.loadFactor = DEFAULT_LOAD_FACTOR;
        putMapEntries(m, false);
    }

    /**
     * 实现Map.putAll和Map构造器。
     *
     * @param m 指定的map
     * @param evict 如果是初始化构造map，则传入false，否则传入true（这个参数和afterNodeInsertion方法有关）
     */
    final void putMapEntries(Map<? extends K, ? extends V> m, boolean evict) {
        int s = m.size();
        if (s > 0) {
            if (table == null) { // 是初始化，先计算出需要的初始容量
                float ft = ((float)s / loadFactor) + 1.0F;
                int t = ((ft < (float)MAXIMUM_CAPACITY) ?
                         (int)ft : MAXIMUM_CAPACITY);
                if (t > threshold)
                    threshold = tableSizeFor(t); // 找到最接近的2的整数次幂，将结果赋给threshold。
            }
            else if (s > threshold)
                resize(); // 需要的容量大于下一次扩容的门槛数量，调用扩容方法。
            for (Map.Entry<? extends K, ? extends V> e : m.entrySet()) { // 把指定map中的元素依次放入初始化的map
                K key = e.getKey();
                V value = e.getValue();
                putVal(hash(key), key, value, false, evict);
            }
        }
    }

    /**
     * 返回map中键值映射关系的数量。
     *
     * @return map中键值映射关系的数量
     */
    public int size() {
        return size;
    }

    /**
     * 如果map中当前没有任何键值映射关系，则返回true
     *
     * @return 如果map中当前没有任何键值映射关系，则返回true
     */
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * 返回给定的键对应的值。
     *
     * 以下两种情况都可能返回null：
     * 1. 映射关系集合中不存在这个key
     * 2. 映射关系集合中存在这个key，但是对应的value是null
     *
     * 用containsKey方法可以区别这两种情况
     */
    public V get(Object key) {
        Node<K,V> e;
        return (e = getNode(hash(key), key)) == null ? null : e.value;
    }

    /**
     * 实现Map.put和相关方法
     *
     * @param hash key的hash值
     * @param key  键
     * @return 对应节点，没找到的话返回null
     */
    final Node<K,V> getNode(int hash, Object key) {
        Node<K,V>[] tab; Node<K,V> first, e; int n; K k;
        if ((tab = table) != null && (n = tab.length) > 0 &&
            (first = tab[(n - 1) & hash]) != null) { // 根据key的hash值和当前table长度得到数组下标，取对应下标的元素
            if (first.hash == hash && // 总是检查一下第一个元素
                ((k = first.key) == key || (key != null && key.equals(k))))
                return first; // hash值和key都相同，刚好就是要找的那个节点
            if ((e = first.next) != null) { // 第一个元素不是，但还能往下找，可能是链表或红黑树
                if (first instanceof TreeNode) // 是红黑树，搜索树节点并返回
                    return ((TreeNode<K,V>)first).getTreeNode(hash, key);
                do { // 是链表
                    if (e.hash == hash &&
                        ((k = e.key) == key || (key != null && key.equals(k))))
                        return e; // 搜索链表节点并返回
                } while ((e = e.next) != null);
            }
        }
        return null; // 对应下标的第一个元素不是要找的，并且next为null，说明不是红黑树也不是链表，判定为找不到元素。
    }

    /**
     * 如果映射关系集合中存在给定的键，则返回true。
     *
     * @param  key 给定的键
     * @return 如果映射关系集合中存在给定的键，则返回true。
     */
    public boolean containsKey(Object key) {
        return getNode(hash(key), key) != null;
    }

    /**
     * 把给定的键值关系放入map中，如果本来就存在这个键，则会覆盖值。
     *
     * @param key   键
     * @param value 值
     * @return 这个键对应的原来的值，如果原来没有这个键，或原来对应的值为null，都会返回null。
     */
    public V put(K key, V value) {
        return putVal(hash(key), key, value, false, true);
    }

    /**
     * 实现Map.put和相关方法
     *
     * @param hash         key的hash值
     * @param key          键
     * @param value        对应需要放入的值
     * @param onlyIfAbsent 如果是true，不改变已有的值
     * @param evict        如果是false，说明table是在初始化模式
     * @return 最早放入的一个值，如果没有就是null
     */
    final V putVal(int hash, K key, V value, boolean onlyIfAbsent,
                   boolean evict) {
        Node<K,V>[] tab; Node<K,V> p; int n, i;
        if ((tab = table) == null || (n = tab.length) == 0)
            n = (tab = resize()).length; // 如果table还没用过，要进行初次使用的resize()，无论有没有进来，n都已赋上当前table长度的值
        if ((p = tab[i = (n - 1) & hash]) == null) // 根据table长度计算最终哈希值
            tab[i] = newNode(hash, key, value, null); // 如果刚好目标位置是空的，创建一个元素放进去
        else { // 如果目标位置有元素了，就有点麻烦了
            Node<K,V> e; K k;
            if (p.hash == hash &&
                ((k = p.key) == key || (key != null && key.equals(k))))
                e = p; // 新元素和原来存在的元素有相同的key，那么操作对象直接指定为旧的那个元素
            else if (p instanceof TreeNode) // 占了位置的这个元素是个树节点，那么按照树的插入规则插入新节点
                e = ((TreeNode<K,V>)p).putTreeVal(this, tab, hash, key, value);
            else { // 以上两种情况都不是，就肯定是要做链表了
                for (int binCount = 0; ; ++binCount) { // 遍历链表
                    if ((e = p.next) == null) { // 遍历到末尾了，还没碰到key相同的元素
                        p.next = newNode(hash, key, value, null); // 把新元素放在链表尾部
                        if (binCount >= TREEIFY_THRESHOLD - 1) // 如果这个新元素是链表的第8个元素，就要把链表转红黑树
                            treeifyBin(tab, hash);
                        break;
                    }
                    if (e.hash == hash &&
                        ((k = e.key) == key || (key != null && key.equals(k))))
                        break; // 遍历链表的过程中，发现这个key已经在某个节点上了，则终止遍历。
                    p = e; // e现在等于p.next，这一步把当前节点的下个节点放入p，实现链表节点的推进
                }
            }
            if (e != null) { // 如果是原先就存在的映射关系，那么这里e是不为null的，如果有疑问可以再走一下上面的判断逻辑感受一下。
                V oldValue = e.value;
                if (!onlyIfAbsent || oldValue == null) // 如果onlyIfAbsent为true，那么只有在原值为null时才会放入新的值。
                    e.value = value;
                afterNodeAccess(e); // LinkedHashMap用的，先不看它
                return oldValue; // 无论如何，把老的值返回去
            }
        }
        ++modCount; // modCount加1，用于检查并发异常
        if (++size > threshold) // 把新的元素数量和原来计算好的扩容门槛比较，如果超出了就进行扩容
            resize();
        afterNodeInsertion(evict); // LinkedHashMap用的，先不看它
        return null; // 无论如何，把老的值返回去，走到这里说明key肯定是新的，老的值必定是null。
    }

    /**
     * 初始化或翻倍table的容量。如果当前table是null，就是初始化，目标容量是预先放在字段threshold中的值。
     * 否则，因为容量是按2的整数次幂膨胀的，原来放在每个容器中的元素，在新的table中要么原地不动，要么偏移2的整数次幂。
     *
     * @return 变化后的table
     */
    final Node<K,V>[] resize() {
        Node<K,V>[] oldTab = table;
        int oldCap = (oldTab == null) ? 0 : oldTab.length;
        int oldThr = threshold;
        int newCap, newThr = 0;
        if (oldCap > 0) { // 如果本次扩容不是初始化
            if (oldCap >= MAXIMUM_CAPACITY) { // 如果原来容量已经超过了最大值
                threshold = Integer.MAX_VALUE; // 就把threshold设为Integer最大值
                return oldTab; // 并返回原来的table
            }
            // 否则容量翻倍，如果翻倍后的容量小于最大容量而且旧的容量大于等于DEFAULT_INITIAL_CAPACITY
            else if ((newCap = oldCap << 1) < MAXIMUM_CAPACITY &&
                     oldCap >= DEFAULT_INITIAL_CAPACITY)
                newThr = oldThr << 1; // 新的扩容门槛变为原来的2倍
        }
        else if (oldThr > 0) // 初始后threshold的值大于0。如果初始化时指定的容量为0，则threshold是1，为什么是1可以看一下构造器HashMap(int)末尾
            newCap = oldThr; // 直接使用预先放入的目标容量
        else { // 到这一步说明初始容量为0并且threshold为0，比较几个构造器后，可以断定使用了无参构造器，故负载因子直接用默认值。
            newCap = DEFAULT_INITIAL_CAPACITY;
            newThr = (int)(DEFAULT_LOAD_FACTOR * DEFAULT_INITIAL_CAPACITY);
        }
        if (newThr == 0) {
            // 到这里有两种可能：
            // 1. 上一个if进了第一个分支，但是oldCap太小或太大，没有执行newThr = oldThr << 1
            // 2. 上一个if进的是第二个分支，可以断定这是初始化后第一次插入数据，且构造器必定指定了初始容量是0，此时newCap = oldThr = 1
            float ft = (float)newCap * loadFactor;
            // 因为这两种可能都跳过了"newThr = oldThr << 1"，所以必须补充计算新的门槛。
            // 如果是因为oldCap太大进来的，这里newThr要确保不能超过Integer.MAX_VALUE，为什么newCap和ft都要判？因为负载因子可能大于1。
            // 此外，oldCap太小的时候，门槛计算也放在这里，因为此时这两种算法"oldThr << 1"和"(float)newCap * loadFactor"不是等价的，
            // 如果指定了初始容量是0，初次放入元素前会扩容，ft = 1 * 0.75 = 0.75，强转int后threshold = 0，于是放入首个元素后又会扩容，
            // 这时newCap = oldCap << 1 = 2，oldThr = 0，没法用"oldThr << 1"计算，而"(float)newCap * loadFactor"总能计算出
            // 合理的值。因此只有在oldCap >= DEFAULT_INITIAL_CAPACITY时新的门槛才会在上面计算。
            newThr = (newCap < MAXIMUM_CAPACITY && ft < (float)MAXIMUM_CAPACITY ?
                      (int)ft : Integer.MAX_VALUE);
        }
        threshold = newThr;
        @SuppressWarnings({"rawtypes","unchecked"})
        Node<K,V>[] newTab = (Node<K,V>[])new Node[newCap];
        table = newTab; // 按照新的容量重建table
        if (oldTab != null) { // 如果老的table中有元素，就把元素转移到新的table
            for (int j = 0; j < oldCap; ++j) {
                Node<K,V> e;
                if ((e = oldTab[j]) != null) {
                    oldTab[j] = null; // 清除旧的table对该元素的引用
                    if (e.next == null) // 这个分支代表原容器中有且仅有一个元素
                        newTab[e.hash & (newCap - 1)] = e; // 重新计算哈希值后放入新的table
                    else if (e instanceof TreeNode) // 这个分支代表原容器中的元素已经组成了树结构
                        ((TreeNode<K,V>)e).split(this, newTab, j, oldCap); // 调用拆分树结构的方法
                    else { // 最后这个分支代表原容器中的元素组成了链表
                        // 因为table的容量翻倍了，所以每个table中每个容器所处的位置可能会变，
                        // 并且如果变化了，变化量是一个固定值（+扩容前的容量大小），具体推导过程可以参考/documents/HashMap.md中的附录一。
                        Node<K,V> loHead = null, loTail = null; // 这行代表哈希值没变化（以下称low链表）
                        Node<K,V> hiHead = null, hiTail = null; // 这行代表哈希值变化了（以下称high链表）
                        Node<K,V> next;
                        do {
                            // 根据e.hash & oldCap是否等于0，将原链表拆成2个链表
                            next = e.next; // 把下一个元素暂存到next中
                            if ((e.hash & oldCap) == 0) {
                                if (loTail == null)
                                    loHead = e; // 如果链表还是空的，把它放在链表头部
                                else
                                    loTail.next = e; // 否则，把它拼接到链表当前尾部的后面
                                loTail = e; // 把它标记为链表的最后一个元素
                            }
                            else {
                                if (hiTail == null)
                                    hiHead = e;
                                else
                                    hiTail.next = e;
                                hiTail = e;
                            }
                        } while ((e = next) != null); // 一直到遍历完整个链表
                        // 把两个链表放到新table中的相应位置
                        if (loTail != null) {
                            loTail.next = null;
                            newTab[j] = loHead;
                        }
                        if (hiTail != null) {
                            hiTail.next = null;
                            newTab[j + oldCap] = hiHead;
                        }
                    }
                }
            }
        }
        return newTab;
    }

    /**
     * 把给定hash对应位置的链表转为红黑树，但如果目前table容量过小（默认为64）则优先考虑扩容。
     */
    final void treeifyBin(Node<K,V>[] tab, int hash) {
        int n, index; Node<K,V> e;
        if (tab == null || (n = tab.length) < MIN_TREEIFY_CAPACITY)
            resize(); // 容量过小，先扩容
        else if ((e = tab[index = (n - 1) & hash]) != null) {
            TreeNode<K,V> hd = null, tl = null;
            do {
                TreeNode<K,V> p = replacementTreeNode(e, null); // 把链表节点依次转化
                if (tl == null)
                    hd = p; // 把第一个被转化的节点指定为头节点
                else {
                    p.prev = tl; // 如果尾节点不为空，把p的prev指针指向它
                    tl.next = p; // 并把它的next指针指向p
                }
                tl = p; // 把最新放入的这个p指定为尾节点
            } while ((e = e.next) != null);
            if ((tab[index] = hd) != null) // 把首元素放到table中对应位置
                hd.treeify(tab); // 以首元素为根节点构建红黑树
        }
    }

    /**
     * 将给定map中的所有映射关系拷贝到当前map中。
     * 如果两个map之间有相同的key，则当前map中该key对应的value会被覆盖
     *
     * @param m 指定的map
     * @throws NullPointerException 如果指定的map是null
     */
    public void putAll(Map<? extends K, ? extends V> m) {
        putMapEntries(m, true);
    }

    /**
     * 如果存在的话，从map中删除指定的key的映射关系
     *
     * @param  key 指定的要删除的key
     * @return 在删除前，该key对应的value，如果value是null，或不存在这个key，都会返回null
     */
    public V remove(Object key) {
        Node<K,V> e;
        return (e = removeNode(hash(key), key, null, false, true)) == null ?
            null : e.value;
    }

    /**
     * 实现Map.remove及其相关方法
     *
     * @param hash 键的哈希值
     * @param key 键
     * @param value 如果matchValue是true，会使用这个值，否则不会用到这个值
     * @param matchValue 如果是true的话，仅当映射关系的值等于入参中的value时才会删除该映射关系
     * @param movable 如果是false，那么在删除时不移动其他节点
     * @return 返回被删除的节点，如果没有的话返回null
     */
    final Node<K,V> removeNode(int hash, Object key, Object value,
                               boolean matchValue, boolean movable) {
        Node<K,V>[] tab; Node<K,V> p; int n, index;
        if ((tab = table) != null && (n = tab.length) > 0 &&
            (p = tab[index = (n - 1) & hash]) != null) { // 确认对应位置是否有元素
            Node<K,V> node = null, e; K k; V v;
            if (p.hash == hash &&
                ((k = p.key) == key || (key != null && key.equals(k))))
                node = p; // 该位置的元素哈希值和key均符合条件，直接将其指定为要删除的元素
            else if ((e = p.next) != null) { // 否则看下该节点是链表或红黑树节点
                if (p instanceof TreeNode) // 是红黑树节点
                    node = ((TreeNode<K,V>)p).getTreeNode(hash, key); // 到红黑树中搜寻目标节点
                else { // 是链表节点，遍历链表直到找到元素或next为空
                    do {
                        if (e.hash == hash &&
                            ((k = e.key) == key ||
                             (key != null && key.equals(k)))) {
                            node = e;
                            break;
                        }
                        p = e; // 注意，如果在链表中找到了目标元素，那么p和node的关系是p.next == node
                    } while ((e = e.next) != null);
                }
            }
            // 找到了要删除的节点，做删除操作。matchValue如果是true，仅当映射关系的值等于入参中的value时才做删除操作
            if (node != null && (!matchValue || (v = node.value) == value ||
                                 (value != null && value.equals(v)))) {
                if (node instanceof TreeNode) // 是树节点，调用红黑树的删除节点操作
                    ((TreeNode<K,V>)node).removeTreeNode(this, tab, movable);
                else if (node == p) // 该位置的第一个节点就是要找的那个元素，将原第二个元素移上来作为首元素，如果没有就放入null
                    tab[index] = node.next;
                else
                    p.next = node.next; // 目标节点是从链表中间节点找到的，移除后把它的前后元素对接起来。
                ++modCount; // modCount加1，用于检查并发异常
                --size; // table当前成员数量-1
                afterNodeRemoval(node); // 做后置处理，LinkedHashMap中会使用
                return node; // 返回这个被删除的节点
            }
        }
        return null; // 没找到要删除的节点
    }

    /**
     * 清除map中所有的映射关系
     */
    public void clear() {
        Node<K,V>[] tab;
        modCount++; // 执行操作前modCount加1，用于检查并发异常
        if ((tab = table) != null && size > 0) {
            size = 0;
            for (int i = 0; i < tab.length; ++i)
                tab[i] = null;
        }
    }

    /**
     * 如果map中有至少一个key的值等于给定的value，则返回true，否则返回false
     *
     * @param value 指定的value
     * @return 如果map中有至少一个key的值等于给定的value，则返回true，否则返回false
     */
    public boolean containsValue(Object value) {
        Node<K,V>[] tab; V v;
        if ((tab = table) != null && size > 0) {
            for (int i = 0; i < tab.length; ++i) {
                for (Node<K,V> e = tab[i]; e != null; e = e.next) { // 相同hash值的元素遍历完整
                    if ((v = e.value) == value ||
                        (value != null && value.equals(v)))
                        return true;
                }
            }
        }
        return false;
    }

    /**
     * 以Set形式返回map中所有的key。这个集合是由map提供支持的，因此map的修改会反映到集合中，反之亦然。
     * 如果map在遍历正在进行时被修改（除了迭代器自己的remove方法），那么迭代的结果会有问题。
     * 该集合支持将元素从map中移除，也就是说如果我们通过Iterator.remove、Set.remove、removeAll、retainAll、clear方法操作这个集合，
     * 会导致map中同样key的映射关系也被移除。但它不支持add或allAll。
     *
     * @return 一个Set形式的key集合
     */
    public Set<K> keySet() {
        Set<K> ks = keySet;
        if (ks == null) {
            ks = new KeySet();
            keySet = ks;
        }
        return ks;
    }

    /**
     * 用于keySet()方法的集合类，继承了AbstractSet，重写了一些方法以适应HashMap的规则。
     */
    final class KeySet extends AbstractSet<K> {
        public final int size()                 { return size; }
        // 清空key意味着清空map
        public final void clear()               { HashMap.this.clear(); }
        // 用HashMap自定义的迭代器
        public final Iterator<K> iterator()     { return new KeyIterator(); }
        public final boolean contains(Object o) { return containsKey(o); }
        // 移除key会导致整个映射关系被移除掉
        public final boolean remove(Object key) {
            return removeNode(hash(key), key, null, false, true) != null;
        }
        // 返回一个包含所有key的分割迭代器
        public final Spliterator<K> spliterator() {
            return new KeySpliterator<>(HashMap.this, 0, -1, 0, 0);
        }
        // 对所有key执行传入的方法
        public final void forEach(Consumer<? super K> action) {
            Node<K,V>[] tab;
            if (action == null)
                throw new NullPointerException();
            if (size > 0 && (tab = table) != null) {
                int mc = modCount;
                for (int i = 0; i < tab.length; ++i) {
                    for (Node<K,V> e = tab[i]; e != null; e = e.next)
                        action.accept(e.key);
                }
                if (modCount != mc) // 如果在迭代过程中map发生变化则抛出异常
                    throw new ConcurrentModificationException();
            }
        }
    }

    /**
     * 返回一个Collection形式的包含map中所有value的集合。
     * 其他规则和keySet()方法相同。
     *
     * @return map中的所有value的集合
     */
    public Collection<V> values() {
        Collection<V> vs = values;
        if (vs == null) {
            vs = new Values();
            values = vs;
        }
        return vs;
    }

    /**
     * 用于values()方法的集合类，继承了AbstractCollection，重写了一些方法以适应HashMap的规则。
     * 方法和KeySet类似
     * 注意这个类没重写remove方法，直接用从AbstractCollection继承来的remove不会删除map的元素，但如果通过迭代器ValueIterator中的删除
     * 方法删除value，是会删除掉map中对应的元素的。
     */
    final class Values extends AbstractCollection<V> {
        public final int size()                 { return size; }
        public final void clear()               { HashMap.this.clear(); }
        public final Iterator<V> iterator()     { return new ValueIterator(); }
        public final boolean contains(Object o) { return containsValue(o); }
        public final Spliterator<V> spliterator() {
            return new ValueSpliterator<>(HashMap.this, 0, -1, 0, 0);
        }
        public final void forEach(Consumer<? super V> action) {
            Node<K,V>[] tab;
            if (action == null)
                throw new NullPointerException();
            if (size > 0 && (tab = table) != null) {
                int mc = modCount;
                for (int i = 0; i < tab.length; ++i) {
                    for (Node<K,V> e = tab[i]; e != null; e = e.next)
                        action.accept(e.value);
                }
                if (modCount != mc)
                    throw new ConcurrentModificationException();
            }
        }
    }

    /**
     * 返回一个Set形式的映射关系集合，这个集合由map提供支持，map的变化会反映到集合中，反之亦然。
     * 如果在这个集合的迭代过程中map被修改（除了通过迭代器自己的remove操作或迭代器返回map元素的setValue操作），将会产生一个不明确的迭代结果。
     * 通过Iterator.remove、Set.remove、removeAll、retainAll和clear方法将元素从这个集合移除时，从map中对应的元素会被对应移除。
     * 但是它不支持add、addAll操作。
     *
     * @return map中包含的所有映射关系的一个集合
     */
    public Set<Map.Entry<K,V>> entrySet() {
        Set<Map.Entry<K,V>> es;
        return (es = entrySet) == null ? (entrySet = new EntrySet()) : es;
    }

    /**
     * 和内部类KeySet和Values类似，它用于entrySet()方法
     */
    final class EntrySet extends AbstractSet<Map.Entry<K,V>> {
        public final int size()                 { return size; }
        public final void clear()               { HashMap.this.clear(); }
        public final Iterator<Map.Entry<K,V>> iterator() {
            return new EntryIterator();
        }
        public final boolean contains(Object o) {
            if (!(o instanceof Map.Entry))
                return false;
            Map.Entry<?,?> e = (Map.Entry<?,?>) o;
            Object key = e.getKey();
            Node<K,V> candidate = getNode(hash(key), key);
            return candidate != null && candidate.equals(e);
        }
        // 注意remove方法，只有key和value都对上的时候才删除。如果成功删除了一个元素，则返回true，否则返回false。
        public final boolean remove(Object o) {
            if (o instanceof Map.Entry) {
                Map.Entry<?,?> e = (Map.Entry<?,?>) o;
                Object key = e.getKey();
                Object value = e.getValue();
                return removeNode(hash(key), key, value, true, true) != null;
            }
            return false;
        }
        public final Spliterator<Map.Entry<K,V>> spliterator() {
            return new EntrySpliterator<>(HashMap.this, 0, -1, 0, 0);
        }
        public final void forEach(Consumer<? super Map.Entry<K,V>> action) {
            Node<K,V>[] tab;
            if (action == null)
                throw new NullPointerException();
            if (size > 0 && (tab = table) != null) {
                int mc = modCount;
                for (int i = 0; i < tab.length; ++i) {
                    for (Node<K,V> e = tab[i]; e != null; e = e.next)
                        action.accept(e);
                }
                if (modCount != mc)
                    throw new ConcurrentModificationException();
            }
        }
    }

    // 重写JDK8 Map接口的一些扩展方法

    // 如果没有找到则返回给定的默认值
    @Override
    public V getOrDefault(Object key, V defaultValue) {
        Node<K,V> e;
        return (e = getNode(hash(key), key)) == null ? defaultValue : e.value;
    }

    // putVal的变种，如果map中已经存在这个key，则不改变值。返回值是这个key对应的原来的值。
    @Override
    public V putIfAbsent(K key, V value) {
        return putVal(hash(key), key, value, true, true);
    }

    // 只有key和value都对上时才删除元素，如果有元素被删除则返回true。
    @Override
    public boolean remove(Object key, Object value) {
        return removeNode(hash(key), key, value, true, true) != null;
    }

    // 如果能找到key和value都相同的元素，则把旧的value替换为新的value。如果替换成功返回true。
    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        Node<K,V> e; V v;
        if ((e = getNode(hash(key), key)) != null &&
            ((v = e.value) == oldValue || (v != null && v.equals(oldValue)))) {
            e.value = newValue;
            afterNodeAccess(e);
            return true;
        }
        return false;
    }

    // 和put方法的不同点是：只有当key已经在map中存在时才更新值。返回旧的值。
    @Override
    public V replace(K key, V value) {
        Node<K,V> e;
        if ((e = getNode(hash(key), key)) != null) {
            V oldValue = e.value;
            e.value = value;
            afterNodeAccess(e);
            return oldValue;
        }
        return null;
    }

    /**
     * 如果原来map中就存在这个key，而且对应value不为null，就直接返回旧值。
     * 如果原来不存在，或虽然存在但value为null，则以key为入参执行mappingFunction方法，其返回值作为key对应的value更新或插入键值对，
     * 然后把这个value返回。
     *
     * @param key 想要设定value值的key，同时也是mappingFunction的入参
     * @param mappingFunction 需要给定的方法，入参类型是map的key类型，返回参数类型是map的value类型
     * @return 如果原来就存在这个key，而且对应value不为null，就直接返回旧值，否则返回mappingFunction的返回值
     */
    @Override
    public V computeIfAbsent(K key,
                             Function<? super K, ? extends V> mappingFunction) {
        if (mappingFunction == null)
            throw new NullPointerException();
        int hash = hash(key);
        Node<K,V>[] tab; Node<K,V> first; int n, i;
        int binCount = 0;
        TreeNode<K,V> t = null;
        Node<K,V> old = null;
        if (size > threshold || (tab = table) == null ||
            (n = tab.length) == 0)
            n = (tab = resize()).length; // 如果长度不够就扩容
        if ((first = tab[i = (n - 1) & hash]) != null) { // 定位到容器并将first指向容器中首个元素
            if (first instanceof TreeNode) // 是红黑树，用红黑树的搜索节点方法确认给定的key是否已存在，如果已存在就把old指向它。
                old = (t = (TreeNode<K,V>)first).getTreeNode(hash, key);
            else { // 不是红黑树，就按普通节点的方式，用next搜索链表
                Node<K,V> e = first; K k;
                do {
                    if (e.hash == hash &&
                        ((k = e.key) == key || (key != null && key.equals(k)))) {
                        old = e; // 如果找到则把old指向这个节点
                        break;
                    }
                    ++binCount; // 统计容器中节点的数量，用于是否要转红黑树的判断
                } while ((e = e.next) != null);
            }
            V oldValue;
            if (old != null && (oldValue = old.value) != null) { // 如果原来就存在这个key，而且对应value不为null，就直接返回旧值
                afterNodeAccess(old);
                return oldValue;
            }
        }
        // map中不存在这个key，则执行给定的方法，以key为入参计算出结果
        V v = mappingFunction.apply(key);
        if (v == null) { // 给定方法返回了null，则返回null，不做put操作
            return null;
        } else if (old != null) { // 计算结果不为null，如果原来存在这个key（可以确定原value是null，否则刚才就返回了）
            old.value = v; // 就将计算结果作为这个key对应的value
            afterNodeAccess(old);
            return v; // 返回value新的值
        }
        else if (t != null) // 计算结果不为null，且key原先不存在于map中。如果是红黑树，按红黑树方法插入一个节点
            t.putTreeVal(this, tab, hash, key, v);
        else { // 如果不是红黑树，按普通节点方式插入
            tab[i] = newNode(hash, key, v, first);
            if (binCount >= TREEIFY_THRESHOLD - 1)
                treeifyBin(tab, hash); // 判断是否要转为红黑树
        }
        ++modCount;
        ++size;
        afterNodeInsertion(true);
        return v; // 返回给定方法的返回结果
    }

    /**
     * 如果原来map中不存在这个key，或对应的value为null，就直接返回null。
     * 如果原来存在，且对应的value不为null，则以key和value为入参执行remappingFunction方法，其返回值如果为null，则要删除该节点，
     * 如果不为null，则替换旧value。最终方法的返回值是remappingFunction的返回值。
     *
     * @param key 给定的key，同时也是remappingFunction的入参之一
     * @param remappingFunction 用来计算新value的方法，入参是给定的key和它对应的value值，返回参数类型是value的类型
     * @return 如果原来map中存在给定的key且对应value不为null，则返回remappingFunction执行后的返回值，否则返回null
     */
    public V computeIfPresent(K key,
                              BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        if (remappingFunction == null)
            throw new NullPointerException();
        Node<K,V> e; V oldValue;
        int hash = hash(key);
        if ((e = getNode(hash, key)) != null &&
            (oldValue = e.value) != null) { // 如果原先map中存在这个key，且对应value不为null
            V v = remappingFunction.apply(key, oldValue); // 则以key和value为入参执行remappingFunction方法，得到新的value
            if (v != null) { // 如果新的value不为null，则替换旧的value，然后返回新的value。
                e.value = v;
                afterNodeAccess(e);
                return v;
            }
            else // 如果新的value是null，则删除掉这个节点
                removeNode(hash, key, null, false, true);
        }
        return null; // 无论是没找到这个节点，还是找到了但旧value或新value为null，都将返回null。
    }

    /**
     * 无论原来map中是否存在这个key，都将以key和其对应的value（不存在则用null）为入参执行remappingFunction方法。
     * 如果key存在，则根据remappingFunction方法的返回值做下一步操作，如果返回值不为null则覆盖原value，如果为null则删除节点。
     * 如果key不存在，那么无论remappingFunction方法返回值是否为null，都将插入一个新节点
     *
     * @param key 指定的key
     * @param remappingFunction 以指定key和其对应value为入参，返回值作为新的value
     * @return 总是返回remappingFunction方法的返回值
     */
    @Override
    public V compute(K key,
                     BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        if (remappingFunction == null)
            throw new NullPointerException();
        int hash = hash(key);
        Node<K,V>[] tab; Node<K,V> first; int n, i;
        int binCount = 0;
        TreeNode<K,V> t = null;
        Node<K,V> old = null;
        if (size > threshold || (tab = table) == null ||
            (n = tab.length) == 0)
            n = (tab = resize()).length;
        if ((first = tab[i = (n - 1) & hash]) != null) {
            if (first instanceof TreeNode)
                old = (t = (TreeNode<K,V>)first).getTreeNode(hash, key);
            else {
                Node<K,V> e = first; K k;
                do {
                    if (e.hash == hash &&
                        ((k = e.key) == key || (key != null && key.equals(k)))) {
                        old = e;
                        break;
                    }
                    ++binCount;
                } while ((e = e.next) != null);
            }
        }
        // 上半部分处理和computeIfAbsent大致相同，唯一的区别是key不存在或value为null时不直接返回了。
        // 拿key和旧的value（若不存在则用null）计算出新的value
        V oldValue = (old == null) ? null : old.value;
        V v = remappingFunction.apply(key, oldValue);
        if (old != null) { // 如果原来map中存在该节点
            if (v != null) { // 且新的value不为null
                old.value = v; // 则用新的value覆盖旧的value
                afterNodeAccess(old);
            }
            else // 但如果新的value是null，则删除掉该节点
                removeNode(hash, key, null, false, true);
        }
        else if (v != null) { // 如果原来map中不存在该节点，那么无论新的值是否为null，都将插入一个新的节点
            if (t != null)
                t.putTreeVal(this, tab, hash, key, v);
            else {
                tab[i] = newNode(hash, key, v, first);
                if (binCount >= TREEIFY_THRESHOLD - 1)
                    treeifyBin(tab, hash);
            }
            ++modCount;
            ++size;
            afterNodeInsertion(true);
        }
        return v;
    }

    /**
     * 如果原来map中key对应的节点存在，则分两种情况计算新value：
     * 1. 旧value不为null，则以旧value和传入的value为入参执行remappingFunction获得新的value
     * 2. 旧value为null，则直接以传入的value作为新的value
     * 新的value如果不为null，则替代旧value，否则删除该节点
     *
     * 如果原来map中key对应的节点不存在，则分两情况处理：
     * 1. 传入的value不为null，则插入一个新节点
     * 2. 传入的value为null，什么都不做
     *
     * @param key 指定的key
     * @param value 即将参与新value计算的value
     * @param remappingFunction 旧value不为null时才执行，入参是旧value和传入的value
     * @return 如果节点存在且value不为null，则返回remappingFunction的计算结果，否则把传入的value传回
     */
    @Override
    public V merge(K key, V value,
                   BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        if (value == null)
            throw new NullPointerException();
        if (remappingFunction == null)
            throw new NullPointerException();
        int hash = hash(key);
        Node<K,V>[] tab; Node<K,V> first; int n, i;
        int binCount = 0;
        TreeNode<K,V> t = null;
        Node<K,V> old = null;
        if (size > threshold || (tab = table) == null ||
            (n = tab.length) == 0)
            n = (tab = resize()).length;
        if ((first = tab[i = (n - 1) & hash]) != null) {
            if (first instanceof TreeNode)
                old = (t = (TreeNode<K,V>)first).getTreeNode(hash, key);
            else {
                Node<K,V> e = first; K k;
                do {
                    if (e.hash == hash &&
                        ((k = e.key) == key || (key != null && key.equals(k)))) {
                        old = e;
                        break;
                    }
                    ++binCount;
                } while ((e = e.next) != null);
            }
        }
        // 上半部分处理和compute方法相同，都是去找key对应的节点
        if (old != null) { // 节点存在
            V v;
            if (old.value != null) // 如果原value不为null，则以原value和merge方法传入的value作为入参执行remappingFunction得到新值
                v = remappingFunction.apply(old.value, value);
            else // 如果原value为null，则直接以merge方法传入的value作为新的value
                v = value;
            if (v != null) { // 如果新的value不为null，则替换旧的value
                old.value = v;
                afterNodeAccess(old);
            }
            else // 如果新的value为null，则删除节点
                removeNode(hash, key, null, false, true);
            return v;
        }
        // 节点不存在
        if (value != null) { // 但如果传入的value不为null，那么就插入一个新的节点
            if (t != null)
                t.putTreeVal(this, tab, hash, key, value);
            else {
                tab[i] = newNode(hash, key, value, first);
                if (binCount >= TREEIFY_THRESHOLD - 1)
                    treeifyBin(tab, hash);
            }
            ++modCount;
            ++size;
            afterNodeInsertion(true);
        }
        // 如果节点不存在且传入的value为null，则返回null，啥也不做
        return value;
    }

    // 遍历map，以key和value为入参执行传入的方法
    @Override
    public void forEach(BiConsumer<? super K, ? super V> action) {
        Node<K,V>[] tab;
        if (action == null)
            throw new NullPointerException();
        if (size > 0 && (tab = table) != null) {
            int mc = modCount;
            for (int i = 0; i < tab.length; ++i) {
                for (Node<K,V> e = tab[i]; e != null; e = e.next)
                    action.accept(e.key, e.value);
            }
            if (modCount != mc)
                throw new ConcurrentModificationException();
        }
    }

    // 遍历map，以key和value为入参执行传入的方法，将方法的返回值作为新的value覆盖原来的value
    @Override
    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        Node<K,V>[] tab;
        if (function == null)
            throw new NullPointerException();
        if (size > 0 && (tab = table) != null) {
            int mc = modCount;
            for (int i = 0; i < tab.length; ++i) {
                for (Node<K,V> e = tab[i]; e != null; e = e.next) {
                    e.value = function.apply(e.key, e.value);
                }
            }
            if (modCount != mc)
                throw new ConcurrentModificationException();
        }
    }

    /* ------------------------------------------------------------ */
    // 克隆与序列化

    /**
     * 返回一个当前HashMap实例的浅拷贝，key和value自身没有被拷贝
     *
     * @return 一个当前map的浅拷贝
     */
    @SuppressWarnings("unchecked")
    @Override
    public Object clone() {
        HashMap<K,V> result;
        try {
            result = (HashMap<K,V>)super.clone(); // 创建一个当前map实例的克隆
        } catch (CloneNotSupportedException e) {
            // 这不会发生，因为HashMap是可克隆的
            throw new InternalError(e);
        }
        result.reinitialize(); // 将克隆来的map初始化
        result.putMapEntries(this, false); // 将原map的所有元素放入新的map
        return result;
    }

    // 这些方法也被用于HashSet的序列化
    final float loadFactor() { return loadFactor; }
    final int capacity() {
        // 用于序列化写入容量，如果table为空，则根据threshold判断，threshold大于0直接写入threshold，否则写入默认容量16
        // 如果table不为空，则直接写入table的长度
        return (table != null) ? table.length :
            (threshold > 0) ? threshold :
            DEFAULT_INITIAL_CAPACITY;
    }

    /**
     * 将该HashMap实例的状态写入到一个数据流中（即序列化）
     *
     * 序列化内容如下
     * @serialData 容量（被capacity()方法的处理过），接着是size（键值映射对的数量），然后是每个键值对的key和value（没有特定的顺序）
     */
    private void writeObject(java.io.ObjectOutputStream s)
        throws IOException {
        int buckets = capacity();
        s.defaultWriteObject(); // threshold、loadfactor没有transient修饰，会被自动写入流，其他一些隐藏的东西也被写入。
        s.writeInt(buckets); // 写入当前的容量
        s.writeInt(size); // 写入当前的键值对数量
        internalWriteEntries(s); // 写入所有的key和value
    }

    /**
     * 根据数据流重建map（即反序列化）
     *
     * @param s 数据流
     * @throws ClassNotFoundException 如果序列化对象的类没找到
     * @throws IOException 发生I/O错误
     */
    private void readObject(java.io.ObjectInputStream s)
        throws IOException, ClassNotFoundException {
        // 将threshold、loadfactor和其他一些隐藏的东西从数据流中读取出来。
        s.defaultReadObject();
        reinitialize(); // 重新初始化map
        if (loadFactor <= 0 || Float.isNaN(loadFactor))
            throw new InvalidObjectException("Illegal load factor: " +
                                             loadFactor);
        s.readInt();                // 读取之前写入的当前的容量，然后什么也不做
        int mappings = s.readInt(); // 读取当前的键值对数量
        if (mappings < 0)
            throw new InvalidObjectException("Illegal mappings count: " +
                                             mappings);
        else if (mappings > 0) { // 如果键值对数量不为0，说明原来不是空的Map，要把原来的键值对放进去
            // 用读取到的负载因子计算出table的size
            // 负载因子范围是0.25...4.0
            float lf = Math.min(Math.max(0.25f, loadFactor), 4.0f);
            float fc = (float)mappings / lf + 1.0f;
            // 如果根据键值对数量和负载因子计算得到的容量小于16，则把容量设为16，如果大于最大限定容量，则设为最大限定容量，
            // 如果容量正常，则取大于该数字的最小的2的整数次幂作为容量
            int cap = ((fc < DEFAULT_INITIAL_CAPACITY) ?
                       DEFAULT_INITIAL_CAPACITY :
                       (fc >= MAXIMUM_CAPACITY) ?
                       MAXIMUM_CAPACITY :
                       tableSizeFor((int)fc));
            // 重新计算扩容门槛
            float ft = (float)cap * lf;
            threshold = ((cap < MAXIMUM_CAPACITY && ft < MAXIMUM_CAPACITY) ?
                         (int)ft : Integer.MAX_VALUE);

            // 用Map.Entry[].class去获取流中的键值对数量，用这个类是因为它是作为Map中的元素最基础的类，能把树节点也计算到
            SharedSecrets.getJavaOISAccess().checkArray(s, Map.Entry[].class, cap);
            @SuppressWarnings({"rawtypes","unchecked"})
            Node<K,V>[] tab = (Node<K,V>[])new Node[cap];
            table = tab;

            // 读取所有的key和value，放到新的map中
            for (int i = 0; i < mappings; i++) {
                @SuppressWarnings("unchecked")
                    K key = (K) s.readObject();
                @SuppressWarnings("unchecked")
                    V value = (V) s.readObject();
                putVal(hash(key), key, value, false, false);
            }
        }
    }

    /* ------------------------------------------------------------ */
    // 自定义迭代器

    abstract class HashIterator {
        Node<K,V> next;        // 下一个要返回的节点
        Node<K,V> current;     // 当前节点
        int expectedModCount;  // 配合fast-fail机制
        int index;             // 当前位置

        HashIterator() {
            expectedModCount = modCount;
            Node<K,V>[] t = table;
            current = next = null;
            index = 0;
            if (t != null && size > 0) { // 初始化时将next指向第一个不为空的节点（如果存在的话）
                do {} while (index < t.length && (next = t[index++]) == null);
            }
        }

        public final boolean hasNext() {
            return next != null;
        }

        final Node<K,V> nextNode() {
            Node<K,V>[] t;
            Node<K,V> e = next;
            if (modCount != expectedModCount) // 检查map是否被修改过
                throw new ConcurrentModificationException();
            if (e == null) // 如果下一个节点是null，调用这个方法将会报错
                throw new NoSuchElementException();
            // 将current指向下个节点，并将next指向下个节点的下一个节点
            if ((next = (current = e).next) == null && (t = table) != null) {
                // 如果next是null的话，一直往后搜索到一个不为空的元素，或table结尾。
                do {} while (index < t.length && (next = t[index++]) == null);
            }
            return e; // 返回current指向的节点
        }

        public final void remove() {
            Node<K,V> p = current;
            if (p == null)
                throw new IllegalStateException();
            if (modCount != expectedModCount) // 检查map是否被修改过
                throw new ConcurrentModificationException();
            current = null;
            K key = p.key;
            // movable为false，如果是红黑树移除节点，有两件事情会被忽略:
            // 1. 判断是否拆解红黑树（除非容器删空了）; 2. 将新的根节点移到容器的首位
            removeNode(hash(key), key, null, false, false);
            expectedModCount = modCount; // 将expectedModCount同步，即此次删除节点对迭代器自己来说不视为map改动
        }
    }

    // 三个自定义迭代器，比较好理解
    final class KeyIterator extends HashIterator
        implements Iterator<K> {
        public final K next() { return nextNode().key; }
    }

    final class ValueIterator extends HashIterator
        implements Iterator<V> {
        public final V next() { return nextNode().value; }
    }

    final class EntryIterator extends HashIterator
        implements Iterator<Map.Entry<K,V>> {
        public final Map.Entry<K,V> next() { return nextNode(); }
    }

    /* ------------------------------------------------------------ */
    // 分割迭代器，借助Spliterator接口实现多线程迭代，提高迭代效率

    static class HashMapSpliterator<K,V> {
        final HashMap<K,V> map;
        Node<K,V> current;          // 当前准备处理的节点
        int index;                  // 当前遍历到的table下标，在分割迭代器或单个/整体迭代时会被修改
        int fence;                  // 当前迭代器遍历的table下标上限（判断时为开区间）
        int est;                    // 需要遍历的元素个数估计
        int expectedModCount;       // 配合HashMap的modCount实现fast-fail机制

        HashMapSpliterator(HashMap<K,V> m, int origin,
                           int fence, int est,
                           int expectedModCount) {
            this.map = m;
            this.index = origin;
            this.fence = fence;
            this.est = est;
            this.expectedModCount = expectedModCount;
        }

        final int getFence() { // 获取需要遍历的table下标上限
            int hi;
            if ((hi = fence) < 0) { // 首次获取时初始化
                HashMap<K,V> m = map;
                est = m.size;
                expectedModCount = m.modCount;
                Node<K,V>[] tab = m.table;
                hi = fence = (tab == null) ? 0 : tab.length;
            }
            return hi;
        }

        public final long estimateSize() {
            getFence(); // 强制初始化
            return (long) est; // 返回需要迭代的元素的数量
        }
    }

    /**
     * 用于map节点的key的分割迭代器
     *
     * @param <K> map的key类型
     * @param <V> map的value类型
     */
    static final class KeySpliterator<K,V>
        extends HashMapSpliterator<K,V>
        implements Spliterator<K> {
        KeySpliterator(HashMap<K,V> m, int origin, int fence, int est,
                       int expectedModCount) {
            super(m, origin, fence, est, expectedModCount);
        }

        /**
         * 对需要迭代的元素进行折半分割（仅分割索引），把一半元素留在当前迭代器中，另一半放在新的迭代器中。
         * 注意它是针对table中一个下标为最小单位操作的，就是说相同hash值一群元素是被当作一个元素的。
         *
         * @return 新的迭代器
         */
        public KeySpliterator<K,V> trySplit() {
            // 将原来的参数折半，前半段留在当前迭代器
            int hi = getFence(), lo = index, mid = (lo + hi) >>> 1;
            // 如果元素数量不够分割，或当前迭代器正忙，则返回null，否则把后半段放在新的迭代器并返回
            return (lo >= mid || current != null) ? null :
                new KeySpliterator<>(map, lo, index = mid, est >>>= 1,
                                        expectedModCount);
        }

        /**
         * 对迭代器中所有剩余的元素执行传入的方法，被tryAdvance处理过的元素将不再处理
         *
         * @param action 需要执行的方法
         */
        public void forEachRemaining(Consumer<? super K> action) {
            int i, hi, mc;
            if (action == null)
                throw new NullPointerException();
            HashMap<K,V> m = map;
            Node<K,V>[] tab = m.table;
            if ((hi = fence) < 0) { // 未初始化，则进行初始化
                mc = expectedModCount = m.modCount; // expectedModCount初始化
                hi = fence = (tab == null) ? 0 : tab.length; // 给fence赋上table的长度
            }
            else
                mc = expectedModCount;
            // 确认table不为空且长度不大于当前迭代器的下标上限
            // 将当前起点索引index存放到i中，然后将本迭代器的下标上限赋值给index
            // 如果i合法，满足以下两个条件之一即可开始执行任务:
            // 1. i小于索引上限
            // 2. current不为空，如果执行过tryAdvance方法就有可能会出现这种情况
            if (tab != null && tab.length >= hi &&
                (i = index) >= 0 && (i < (index = hi) || current != null)) {
                Node<K,V> p = current;
                current = null; // 清除current，阻断下一次forEachRemaining方法，保证连续两次调用不会重复执行
                do {
                    if (p == null) // 跳过null节点
                        p = tab[i++];
                    else {
                        action.accept(p.key); // 以遍历到的节点的key为入参，执行传入的方法
                        p = p.next; // 继续下一个节点
                    }
                } while (p != null || i < hi);
                if (m.modCount != mc) // 如果遍历过程中map结构发生了变化，则抛出异常
                    throw new ConcurrentModificationException();
            }
        }

        /**
         * 对迭代器中的下一个元素执行传入的方法
         *
         * @param action 需要执行的方法
         * @return 有元素执行了方法则返回true，如果没有则返回false
         */
        public boolean tryAdvance(Consumer<? super K> action) {
            int hi;
            if (action == null)
                throw new NullPointerException();
            Node<K,V>[] tab = map.table;
            if (tab != null && tab.length >= (hi = getFence()) && index >= 0) {
                while (current != null || index < hi) {
                    if (current == null) // 如果当前遍历到的节点是null
                        current = tab[index++]; // 取下一个元素并把索引后移一位
                    else { // 如果当前遍历到的节点不是null，则以该节点的key为入参执行传入的方法，并将下一个节点放到current里
                        K k = current.key;
                        current = current.next;
                        action.accept(k);
                        if (map.modCount != expectedModCount) // 遍历过程中map结构发生了变化，则抛出异常
                            throw new ConcurrentModificationException();
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * 返回当前迭代器中元素集合的特征，例如：
         * Spliterator.SIZED：数量准确的。
         * Spliterator.DISTINCT：唯一的。
         * fence < 0意味着数量还没初始化，est和map数量不相等表示迭代器创建后map又变了，因此这两种情况下不具有Spliterator.SIZED属性。
         * 下面的ValueSpliterator，由于map的value不一定唯一，所以没有Spliterator.DISTINCT属性。
         *
         * @return 元素集合的特征
         */
        public int characteristics() {
            return (fence < 0 || est == map.size ? Spliterator.SIZED : 0) |
                Spliterator.DISTINCT;
        }
    }

    /**
     * 用于map节点的value的分割迭代器
     * 代码注释参考key分割迭代器，处理过程是一样的
     *
     * @param <K> map的key类型
     * @param <V> map的value类型
     */
    static final class ValueSpliterator<K,V>
        extends HashMapSpliterator<K,V>
        implements Spliterator<V> {
        ValueSpliterator(HashMap<K,V> m, int origin, int fence, int est,
                         int expectedModCount) {
            super(m, origin, fence, est, expectedModCount);
        }

        public ValueSpliterator<K,V> trySplit() {
            int hi = getFence(), lo = index, mid = (lo + hi) >>> 1;
            return (lo >= mid || current != null) ? null :
                new ValueSpliterator<>(map, lo, index = mid, est >>>= 1,
                                          expectedModCount);
        }

        public void forEachRemaining(Consumer<? super V> action) {
            int i, hi, mc;
            if (action == null)
                throw new NullPointerException();
            HashMap<K,V> m = map;
            Node<K,V>[] tab = m.table;
            if ((hi = fence) < 0) {
                mc = expectedModCount = m.modCount;
                hi = fence = (tab == null) ? 0 : tab.length;
            }
            else
                mc = expectedModCount;
            if (tab != null && tab.length >= hi &&
                (i = index) >= 0 && (i < (index = hi) || current != null)) {
                Node<K,V> p = current;
                current = null;
                do {
                    if (p == null)
                        p = tab[i++];
                    else {
                        action.accept(p.value);
                        p = p.next;
                    }
                } while (p != null || i < hi);
                if (m.modCount != mc)
                    throw new ConcurrentModificationException();
            }
        }

        public boolean tryAdvance(Consumer<? super V> action) {
            int hi;
            if (action == null)
                throw new NullPointerException();
            Node<K,V>[] tab = map.table;
            if (tab != null && tab.length >= (hi = getFence()) && index >= 0) {
                while (current != null || index < hi) {
                    if (current == null)
                        current = tab[index++];
                    else {
                        V v = current.value;
                        current = current.next;
                        action.accept(v);
                        if (map.modCount != expectedModCount)
                            throw new ConcurrentModificationException();
                        return true;
                    }
                }
            }
            return false;
        }

        public int characteristics() {
            return (fence < 0 || est == map.size ? Spliterator.SIZED : 0);
        }
    }

    /**
     * 用于map节点整体的分割迭代器
     * 代码注释参考key分割迭代器，处理过程是一样的
     *
     * @param <K> map的key类型
     * @param <V> map的value类型
     */
    static final class EntrySpliterator<K,V>
        extends HashMapSpliterator<K,V>
        implements Spliterator<Map.Entry<K,V>> {
        EntrySpliterator(HashMap<K,V> m, int origin, int fence, int est,
                         int expectedModCount) {
            super(m, origin, fence, est, expectedModCount);
        }

        public EntrySpliterator<K,V> trySplit() {
            int hi = getFence(), lo = index, mid = (lo + hi) >>> 1;
            return (lo >= mid || current != null) ? null :
                new EntrySpliterator<>(map, lo, index = mid, est >>>= 1,
                                          expectedModCount);
        }

        public void forEachRemaining(Consumer<? super Map.Entry<K,V>> action) {
            int i, hi, mc;
            if (action == null)
                throw new NullPointerException();
            HashMap<K,V> m = map;
            Node<K,V>[] tab = m.table;
            if ((hi = fence) < 0) {
                mc = expectedModCount = m.modCount;
                hi = fence = (tab == null) ? 0 : tab.length;
            }
            else
                mc = expectedModCount;
            if (tab != null && tab.length >= hi &&
                (i = index) >= 0 && (i < (index = hi) || current != null)) {
                Node<K,V> p = current;
                current = null;
                do {
                    if (p == null)
                        p = tab[i++];
                    else {
                        action.accept(p);
                        p = p.next;
                    }
                } while (p != null || i < hi);
                if (m.modCount != mc)
                    throw new ConcurrentModificationException();
            }
        }

        public boolean tryAdvance(Consumer<? super Map.Entry<K,V>> action) {
            int hi;
            if (action == null)
                throw new NullPointerException();
            Node<K,V>[] tab = map.table;
            if (tab != null && tab.length >= (hi = getFence()) && index >= 0) {
                while (current != null || index < hi) {
                    if (current == null)
                        current = tab[index++];
                    else {
                        Node<K,V> e = current;
                        current = current.next;
                        action.accept(e);
                        if (map.modCount != expectedModCount)
                            throw new ConcurrentModificationException();
                        return true;
                    }
                }
            }
            return false;
        }

        public int characteristics() {
            return (fence < 0 || est == map.size ? Spliterator.SIZED : 0) |
                Spliterator.DISTINCT;
        }
    }

    /* ------------------------------------------------------------ */
    // LinkedHashMap支持


    /*
     * 下面几个package-protected方法是提供给LinkedHashMap重写用的，不会出现在其他任何子类中。
     * 其他大部分的内部方法虽然是final的，但都是package-protected的，可以在LinkedHashMap、HashSet等类中使用
     */

    // 创建一个普通的非树节点
    Node<K,V> newNode(int hash, K key, V value, Node<K,V> next) {
        return new Node<>(hash, key, value, next);
    }

    // 提供给树节点转为普通节点的方法使用
    Node<K,V> replacementNode(Node<K,V> p, Node<K,V> next) {
        return new Node<>(p.hash, p.key, p.value, next);
    }

    // 创建一个树节点
    TreeNode<K,V> newTreeNode(int hash, K key, V value, Node<K,V> next) {
        return new TreeNode<>(hash, key, value, next);
    }

    // 供treeifyBin方法使用
    TreeNode<K,V> replacementTreeNode(Node<K,V> p, Node<K,V> next) {
        return new TreeNode<>(p.hash, p.key, p.value, next);
    }

    /**
     * 把所有参数设为初始状态，被克隆和readObject方法调用
     */
    void reinitialize() {
        table = null;
        entrySet = null;
        keySet = null;
        values = null;
        modCount = 0;
        threshold = 0;
        size = 0;
    }

    // LinkedHashMap使用的，在某些操作时做后置处理
    void afterNodeAccess(Node<K,V> p) { }
    void afterNodeInsertion(boolean evict) { }
    void afterNodeRemoval(Node<K,V> p) { }

    // 仅被writeObject方法调用，将所有节点的key和value写入数据流
    void internalWriteEntries(java.io.ObjectOutputStream s) throws IOException {
        Node<K,V>[] tab;
        if (size > 0 && (tab = table) != null) {
            for (int i = 0; i < tab.length; ++i) {
                for (Node<K,V> e = tab[i]; e != null; e = e.next) {
                    s.writeObject(e.key);
                    s.writeObject(e.value);
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    // 以下HashMap的剩余代码，都用于定义内部类红黑树。

    /**
     * 树结构容器的元素。继承LinkedHashMap.Entry（LinkedHashMap.Entry又继承了HashMap.Node），
     * 因此除了扮演树结构的元素外，它还能用于链表节点或普通的节点。
     */
    static final class TreeNode<K,V> extends LinkedHashMap.Entry<K,V> {
        TreeNode<K,V> parent; // 父节点
        TreeNode<K,V> left;   // 左子节点
        TreeNode<K,V> right;  // 右子节点
        TreeNode<K,V> prev;   // 用于节点删除时能够找到它的前继元素，如果没有这个指针，它的后继元素会找不到对接人。
        boolean red;
        TreeNode(int hash, K key, V val, Node<K,V> next) {
            super(hash, key, val, next);
        }

        /**
         * 返回包含当前节点的红黑树的根节点
         */
        final TreeNode<K,V> root() {
            for (TreeNode<K,V> r = this, p;;) {
                if ((p = r.parent) == null)
                    return r;
                r = p;
            }
        }

        /**
         * 确保给定的根节点是容器（相同哈希值的key放在一起，这个整体称为容器）中的第一个节点
         */
        static <K,V> void moveRootToFront(Node<K,V>[] tab, TreeNode<K,V> root) {
            int n;
            if (root != null && tab != null && (n = tab.length) > 0) {
                int index = (n - 1) & root.hash;  // 先根据table长度和root的哈希值找到root所在位置。
                TreeNode<K,V> first = (TreeNode<K,V>)tab[index];
                if (root != first) { // 取出该容器中的第一个节点，如果不是root的话就需要做一些处理。
                    Node<K,V> rn;
                    tab[index] = root; // 直接把root放到容器中的首位。
                    TreeNode<K,V> rp = root.prev;
                    if ((rn = root.next) != null)  // 如果root存在next元素，就把next元素的prev指向root的prev
                        ((TreeNode<K,V>)rn).prev = rp;
                    if (rp != null) // 如果root存在prev元素，就把prev元素的next指向root的next
                        rp.next = rn; //（上面两步就是把root从关系链中移除并把它的前后两个元素对接起来）
                    if (first != null)
                        first.prev = root;  // 把原来的首元素的prev指针指向root
                    root.next = first; // 把root的next指针指向原来的首元素
                    root.prev = null; // 因为root现在是首元素，清空它的prev
                }
                assert checkInvariants(root); // 断言确保树结构规范
            }
        }

        /**
         * 从根节点开始寻找指定hash和key对应的节点，参数kc是comparableClassFor(k)方法调用结果的缓存。
         */
        final TreeNode<K,V> find(int h, Object k, Class<?> kc) {
            TreeNode<K,V> p = this;
            do {
                int ph, dir; K pk;
                TreeNode<K,V> pl = p.left, pr = p.right, q;
                if ((ph = p.hash) > h)
                    p = pl; // 目标哈希值小于当前节点哈希值，取左边的子节点继续遍历
                else if (ph < h)
                    p = pr; // 目标哈希值大于当前节点哈希值，取右边的子节点继续遍历
                else if ((pk = p.key) == k || (k != null && k.equals(pk)))
                    return p; // 目标哈希值等于当前节点哈希值，且目标key等于当前节点的key，这个就是要找的节点，返回这个节点。
                else if (pl == null) // 目标哈希值等于当前节点哈希值，但是key不匹配
                    p = pr; // 左边没有节点了，搜右边，右边可能有节点
                else if (pr == null)
                    p = pl; // 右边没有节点了，搜左边，左边可能有节点
                else if ((kc != null || // 左右两边都有节点
                          (kc = comparableClassFor(k)) != null) &&
                         (dir = compareComparables(kc, k, pk)) != 0)
                    p = (dir < 0) ? pl : pr; // 如果k和当前的p的key是可以比较的，则根据比较的大小结果决定搜寻左边还是右边
                else if ((q = pr.find(h, k, kc)) != null) // 如果是不可比较的，先递归搜索右边
                    return q; // 右边找到了就返回
                else
                    p = pl; // 右边没找到，就往左边找
            } while (p != null);
            return null;
        }

        /**
         * 根据哈希值和key获取红黑树节点
         */
        final TreeNode<K,V> getTreeNode(int h, Object k) {
            return ((parent != null) ? root() : this).find(h, k, null); // 先定位到根节点，然后从根节点开始找
        }

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
         * 把链表重构为树
         */
        final void treeify(Node<K,V>[] tab) {
            TreeNode<K,V> root = null;
            for (TreeNode<K,V> x = this, next; x != null; x = next) { // 从当前节点开始根据链表结构遍历
                next = (TreeNode<K,V>)x.next;
                x.left = x.right = null;
                if (root == null) { // 根节点如果还没指定，就把当前节点指定为根节点
                    x.parent = null;
                    x.red = false; // 根据红黑树规则，根节点固定为黑色
                    root = x;
                }
                else { // 如果已经有根节点了，则按照规则插入元素
                    K k = x.key;
                    int h = x.hash;
                    Class<?> kc = null;
                    for (TreeNode<K,V> p = root;;) { // 从根节点开始找，这个新的元素应该放在哪里
                        int dir, ph;
                        K pk = p.key;
                        if ((ph = p.hash) > h)
                            dir = -1; // 哈希值小于当前节点，要往左边找，dir赋值为1
                        else if (ph < h)
                            dir = 1; // 哈希值大于当前节点，要往右边找，dir赋值为-1
                        else if ((kc == null && // 哈希值相等，要做进一步比较
                                  (kc = comparableClassFor(k)) == null) ||
                                 (dir = compareComparables(kc, k, pk)) == 0)
                            dir = tieBreakOrder(k, pk); // 如果key是不可比较的类型，或可以比较但结果相等，就采取最终手段比出大小

                        TreeNode<K,V> xp = p; // 下一步p就要被指向左节点或右节点了，先把它放到xp里备用
                        // 根据刚才比较得到的dir值判断应该往左边找还是往右边找，如果目标节点刚好是空的，就可以把新元素放入了。
                        if ((p = (dir <= 0) ? p.left : p.right) == null) {
                            x.parent = xp; // 把当前节点的parent指向xp。
                            if (dir <= 0) // 把xp对应方向的子节点指向当前节点。
                                xp.left = x;
                            else
                                xp.right = x;
                            root = balanceInsertion(root, x); // 调整红黑树的平衡后返回新的根节点
                            break;
                        }
                    }
                }
            }
            moveRootToFront(tab, root); // 插入元素过程中红黑树可能进行了旋转，所以要确保树的根节点是容器的第一个节点
        }

        /**
         * 将树结构拆为链表结构
         */
        final Node<K,V> untreeify(HashMap<K,V> map) {
            Node<K,V> hd = null, tl = null;
            for (Node<K,V> q = this; q != null; q = q.next) {
                Node<K,V> p = map.replacementNode(q, null); // 将树节点转为普通的节点p，next固定为空
                if (tl == null)
                    hd = p; // 如果尾节点为空，则把p作为尾节点
                else
                    tl.next = p;  // 如果尾节点不为空，则把尾节点的next指针指向p
                tl = p; // 把最新放入的这个p指定为尾节点
            }
            return hd; // 返回新链表的头部
        }

        /**
         * 树结构版本的putVal。
         */
        final TreeNode<K,V> putTreeVal(HashMap<K,V> map, Node<K,V>[] tab,
                                       int h, K k, V v) {
            Class<?> kc = null;
            boolean searched = false;
            TreeNode<K,V> root = (parent != null) ? root() : this; // 先找到当前节点所在的红黑树的根节点
            for (TreeNode<K,V> p = root;;) { // 从根节点开始遍历
                int dir, ph; K pk;
                if ((ph = p.hash) > h)
                    dir = -1; // 如果当前节点的hash值大于目标哈希值，dir赋值为-1
                else if (ph < h)
                    dir = 1; // 如果当前节点的hash值小于目标哈希值，dir赋值为1
                else if ((pk = p.key) == k || (k != null && k.equals(pk)))
                    return p; // 如果哈希值相等，且key相等，则返回这个节点，注意并没有改变value
                else if ((kc == null &&
                          (kc = comparableClassFor(k)) == null) ||
                         (dir = compareComparables(kc, k, pk)) == 0) { // 如果key是不可比较的类型，或者即使可以比较但结果相等
                    if (!searched) { // 进行仅一次的全树遍历
                        TreeNode<K,V> q, ch;
                        searched = true;
                        if (((ch = p.left) != null &&
                             (q = ch.find(h, k, kc)) != null) ||
                            ((ch = p.right) != null &&
                             (q = ch.find(h, k, kc)) != null))
                            return q;  // 在遍历时找到了目标节点，将其返回，注意并没有改变value
                    }
                    dir = tieBreakOrder(k, pk);  // 已经全树遍历过，没找到想找的节点，使用最终解决方案比出大小
                }

                TreeNode<K,V> xp = p; // 把p存放到xp里去，因为下一步p要指向其他节点了。
                if ((p = (dir <= 0) ? p.left : p.right) == null) { // 根据dir的正负决定向左边找还是右边找，如果找到了空位
                    Node<K,V> xpn = xp.next;
                    TreeNode<K,V> x = map.newTreeNode(h, k, v, xpn); // 创建一个新的树节点，同时保留链表的next关系
                    if (dir <= 0) // 根据dir的正负把新的节点x放到xp的左边或右边（指左子节点或右子节点）
                        xp.left = x;
                    else
                        xp.right = x;
                    xp.next = x; // 最后x、xp、xpn的next关系是：xp -> x -> xpn。这样红黑树和链表的遍历顺序达成了共识。
                    x.parent = x.prev = xp; // 构造prev关系：xp <- x；构造parent关系：x的parent指针指向xp
                    if (xpn != null) // 如果xpn存在的话，再构造一下它和x的prev关系，最终结果：xp <- x <-  xpn
                        ((TreeNode<K,V>)xpn).prev = x;
                    // 重新平衡红黑树，并且确保红黑树的根节点是容器（相同哈希值的key放在一起，这个整体称为容器）中的第一个节点
                    moveRootToFront(tab, balanceInsertion(root, x));
                    return null; // 因为创建新节点时把赋值操作都做了，就不需要返回节点信息了
                }
            }
        }

        /**
         * 删除当前节点，这个方法比典型的红黑树元素移除代码要复杂。不能忘记红黑树节点它也要维护作为链表节点的属性，
         * 要是直接删除了，等转回链表的时候它的前后节点就找不到彼此了，所以要把它们先接一下。
         */
        final void removeTreeNode(HashMap<K,V> map, Node<K,V>[] tab,
                                  boolean movable) {
            int n;
            if (tab == null || (n = tab.length) == 0)
                return;
            int index = (n - 1) & hash;
            TreeNode<K,V> first = (TreeNode<K,V>)tab[index], root = first, rl;
            TreeNode<K,V> succ = (TreeNode<K,V>)next, pred = prev; // 先取到当前节点的next节点和prev节点
            if (pred == null) // 没有prev节点 ，说明要删的元素是容器中的首个元素
                tab[index] = first = succ; // 把next节点升级为首节点
            else // 有prev节点 ，要删除的元素不是首个元素
                pred.next = succ; // 把前后两个节点拼接起来，prev节点的next指针指向next节点
            if (succ != null) // 有next节点，调整一下next节点的prev指针，使其指向prev节点
                succ.prev = pred; // ----------至此节点之间的next和prev关系已处理完成，后面开始处理树节点关系----------
            if (first == null)
                return; // 树中已经没有节点了，直接返回。
            if (root.parent != null)
                root = root.root(); // 先找到根节点
            if (root == null // 没有根节点
                || (movable  // 如果movable为true的话，以下三种情况满足其一就会拆解红黑树：
                    && (root.right == null          // 1. 根节点的右子节点为空
                        || (rl = root.left) == null // 2. 根节点的左子节点为空
                        || rl.left == null))) {     // 3. 根节点的左子节点的左子节点为空
                tab[index] = first.untreeify(map);  // 拆解红黑树
                return;
            }
            TreeNode<K,V> p = this, pl = left, pr = right, replacement;
            if (pl != null && pr != null) { // 当前节点的左右子节点都不为空
                TreeNode<K,V> s = pr, sl;
                while ((sl = s.left) != null)
                    s = sl; // 找到右子树中最小的节点作为继承者（放到当前这个要删除的节点的位置）
                boolean c = s.red; s.red = p.red; p.red = c; // 交换继承者（以下简称s）和当前节点（以下简称p）的颜色
                TreeNode<K,V> sr = s.right; // 继承者的右子节点（以下简称sr）
                TreeNode<K,V> pp = p.parent;  // 当前节点的父节点，稍后会成为s的父节点（以下简称pp）
                if (s == pr) { // p是s的直接父节点，也就意味着s没有左子节点
                    p.parent = s; // 交换p和s之间的位置
                    s.right = p;
                }
                else { // p不是s的直接父节点，还是交换p和s之间的位置
                    TreeNode<K,V> sp = s.parent;
                    if ((p.parent = sp) != null) { // p.parent -> s.parent
                        if (s == sp.left) // (sp.left -> p) or (sp.right -> p)
                            sp.left = p;
                        else
                            sp.right = p;
                    }
                    if ((s.right = pr) != null) // s.right -> p.right
                        pr.parent = s; // p.right.parent -> s
                }
                p.left = null; // 由于s节点是沿着p.right节点的左子树一直找到底得到的，所以p和s交换位置之后p必定没有左子节点
                if ((p.right = sr) != null) // p.right -> s.right
                    sr.parent = p; // s.right.parent -> p
                if ((s.left = pl) != null) // s.left -> p.left
                    pl.parent = s; // p.left.parent -> s
                if ((s.parent = pp) == null) // s.parent -> p.parent
                    root = s; // 如果p没有父节点，说明它是根节点，交换后s即为根节点
                else if (p == pp.left) // 如果p有父节点，且p是pp的左子节点
                    pp.left = s; // pp.left -> s
                else
                    pp.right = s; // 如果p是pp的右子节点，则pp.right -> s
                if (sr != null)
                    replacement = sr; // 如果s有右子节点，将其标记为关注点，关注点代表下一步要判断平衡的子树的根节点
                else
                    replacement = p; // 如果s没有子节点，则关注p自己
            }
            else if (pl != null)
                replacement = pl; // 当前节点只有左子节点不为空，将左子节点标记为关注点（p删除后只需将pl上移即可）
            else if (pr != null)
                replacement = pr; // 当前节点只有右子节点不为空，将右子节点标记为关注点（p删除后只需将pr上移即可）
            else
                replacement = p; // 当前节点的左右子节点都为空，将当前节点自己标记为关注点
            // 注意如果有继承者，此时p已经在继承者的位置了，否则还是在原位。整合几种情况之后，仅当p在当前位置有子节点的情况下该判断为true。
            if (replacement != p) {
                // 这一步将p的子节点的指针指向p的父节点，完成交接
                TreeNode<K,V> pp = replacement.parent = p.parent;
                if (pp == null)
                    root = replacement; // 特殊情况判断
                else if (p == pp.left) // 左右判断
                    pp.left = replacement;
                else
                    pp.right = replacement;
                p.left = p.right = p.parent = null; // 清除一下p的各个指针
            }

            // 如果删除的是黑色的节点（如果有继承者，判断的是继承者原来那个位置的颜色），需要调整平衡
            TreeNode<K,V> r = p.red ? root : balanceDeletion(root, replacement);

            if (replacement == p) {  // 清除一下p的parent指针以及父节点的left/right指针
                TreeNode<K,V> pp = p.parent;
                p.parent = null;
                if (pp != null) {
                    if (p == pp.left)
                        pp.left = null;
                    else if (p == pp.right)
                        pp.right = null;
                }
            }
            // 根据movable决定要不要把新的根节点挪到容器的首位。
            if (movable)
                moveRootToFront(tab, r);
        }

        /**
         * 扩容时如果遇到树结构节点则会调用该方法，因为table的容量翻倍了，所以原来哈希值相同的这些节点，新的哈希值可能会变化，
         * 并且如果变化了，变化量是一个固定值（+扩容前的容量大小），具体推导过程可以参考/documents/HashMap.md中的附录一。
         * 所以最终这些节点会被分到1个或2个容器中。
         *
         * @param map 给定的map
         * @param tab 当前容器的头节点所在的table
         * @param index 当前容器的头节点在table中的位置索引
         * @param bit 扩容前的容量大小
         */
        final void split(HashMap<K,V> map, Node<K,V>[] tab, int index, int bit) {
            TreeNode<K,V> b = this;
            // 把节点分到lo和hi两个容器中，分别保留原来的next关系
            TreeNode<K,V> loHead = null, loTail = null;
            TreeNode<K,V> hiHead = null, hiTail = null;
            int lc = 0, hc = 0;
            for (TreeNode<K,V> e = b, next; e != null; e = next) {
                next = (TreeNode<K,V>)e.next;
                e.next = null; // 清除掉原来的next指针
                if ((e.hash & bit) == 0) {
                    if ((e.prev = loTail) == null)
                        loHead = e;
                    else
                        loTail.next = e; // 重新设定next指针
                    loTail = e;
                    ++lc;
                }
                else {
                    if ((e.prev = hiTail) == null)
                        hiHead = e;
                    else
                        hiTail.next = e;
                    hiTail = e;
                    ++hc;
                }
            }

            if (loHead != null) { // lo容器不为空
                if (lc <= UNTREEIFY_THRESHOLD) // 且lo容器中节点数量小于6，则需要拆解该容器的红黑树
                    tab[index] = loHead.untreeify(map);
                else { // 但如果lo容器中节点数量大于等于6，则需要组成红黑树
                    tab[index] = loHead;
                    // 只有在hi容器不为空时才组，因为如果hi容器为空，说明拆解过程中所有节点都被留在了原来的容器，显然不需要做任何改动
                    if (hiHead != null)
                        loHead.treeify(tab);
                }
            }
            if (hiHead != null) { // hi容器不为空
                if (hc <= UNTREEIFY_THRESHOLD) // 且hi容器中节点数量小于6，则需要拆解该容器的红黑树
                    tab[index + bit] = hiHead.untreeify(map);
                else { // 但如果hi容器中节点数量大于等于6，则需要组成红黑树
                    tab[index + bit] = hiHead;
                    // 只有在lo容器不为空时才组，因为如果lo容器为空，说明拆解过程中所有节点都转移到了hi容器，树结构没有被破坏，不需要重组
                    if (loHead != null)
                        hiHead.treeify(tab);
                }
            }
        }

        /* ------------------------------------------------------------ */
        // 红黑树方法，均改编自CLR(Common Language Runtime)

        // 围绕指定节点p左旋
        static <K,V> TreeNode<K,V> rotateLeft(TreeNode<K,V> root,
                                              TreeNode<K,V> p) {
            TreeNode<K,V> r, pp, rl;
            if (p != null && (r = p.right) != null) { // p不为空且其右子节点（以下称r）不为空，才能逆行左旋
                if ((rl = p.right = r.left) != null) // 把r左子节点变为p的右子节点
                    rl.parent = p;                   // 如果这个节点不为空的话，同时将其父节点指针指向p
                if ((pp = r.parent = p.parent) == null) // 把r的父节点指针指向p的父节点（以下称pp）
                    (root = r).red = false; // 如果pp是个根节点，把整棵树的root指针指向pp，并涂黑
                else if (pp.left == p) // 如果pp不是根节点，且在旋转前p是pp的左子节点
                    pp.left = r;       // 那么就把r变成pp的左子节点
                else                   // 而如果在旋转前p是pp右子节点
                    pp.right = r;      // 那么就把r变成pp的右子节点
                r.left = p; // 最后两步做旋转，把p放到r的左子节点处
                p.parent = r;
            }
            return root;
        }

        // 围绕指定节点p右旋
        static <K,V> TreeNode<K,V> rotateRight(TreeNode<K,V> root,
                                               TreeNode<K,V> p) {
            TreeNode<K,V> l, pp, lr;
            if (p != null && (l = p.left) != null) { // p不为空且其左子节点（以下称l）不为空，才能逆行右旋
                if ((lr = p.left = l.right) != null) // 把l的右子节点变为p的左子节点
                    lr.parent = p;                   // 如果这个节点不为空的话，同时将其父节点指针指向p
                if ((pp = l.parent = p.parent) == null) // 把l的父节点指针指向p的父节点（以下称pp）
                    (root = l).red = false; // 如果pp是个根节点，把整棵树的root指针指向pp，并涂黑
                else if (pp.right == p) // 如果pp不是根节点，且在旋转前p是pp的右子节点
                    pp.right = l;       // 那么就把r变成pp的右子节点
                else                    // 而如果在旋转前p是pp左子节点
                    pp.left = l;        // 那么就把r变成pp的左子节点
                l.right = p; // 最后两步做旋转，把p放到l的右子节点处
                p.parent = l;
            }
            return root;
        }

        // 用于插入元素时保持红黑树平衡
        static <K,V> TreeNode<K,V> balanceInsertion(TreeNode<K,V> root,
                                                    TreeNode<K,V> x) {
            x.red = true; // 新插入的节点总是红节点
            for (TreeNode<K,V> xp, xpp, xppl, xppr;;) { // 从新插入的节点开始往上调整
                if ((xp = x.parent) == null) {
                    x.red = false;
                    return x; // 当前节点没有父节点，是根节点，涂黑后直接返回
                }
                else if (!xp.red || (xpp = xp.parent) == null)
                    // (xpp = xp.parent) == null这里要注意一下，首先父节点是红色时这个判断才不会被短路，其次又没有祖父节点，
                    // 这种情况只有根节点为红色才符合，而按照红黑树的规则，根节点必须是黑色的，故这个判断恒为false。
                    // 这里写下这个判断只是为了让xpp指向祖父节点。
                    return root; // 父节点是黑色，不需要再继续调整，返回当前的根节点。
                if (xp == (xppl = xpp.left)) { // 如果父节点是祖父节点的左子节点
                    if ((xppr = xpp.right) != null && xppr.red) { // 叔叔节点（指祖父节点的另一个子节点）不为空且为红色
                        xppr.red = false; // 叔叔节点变黑
                        xp.red = false; // 父节点变黑
                        xpp.red = true; // 祖父节点变红
                        x = xpp; // 把祖父节点标记为当前节点，继续调整
                    }
                    else { // 叔叔节点为空或为黑色
                        if (x == xp.right) { // 如果当前节点是父节点的右子节点
                            // 以父节点为基准进行左旋，*注意*，这里把x的父节点交给x了，该分支中之后的描述语句主语都是这个新的x。
                            root = rotateLeft(root, x = xp);
                            xpp = (xp = x.parent) == null ? null : xp.parent;
                        }
                        if (xp != null) { // 如果父节点不为空
                            xp.red = false; // 将父节点变为黑色
                            if (xpp != null) { // 如果祖父节点不为空
                                xpp.red = true; // 把祖父节点变为红色
                                root = rotateRight(root, xpp); // 以祖父节点为基准进行右旋
                            }
                        }
                    }
                }
                else { // 如果父节点是祖父节点的右子节点
                    if (xppl != null && xppl.red) { // 叔叔节点（指祖父节点的另一个子节点）不为空且为红色
                        xppl.red = false; // 叔叔节点变黑
                        xp.red = false; // 父节点变黑
                        xpp.red = true; // 祖父节点变红
                        x = xpp; // 把祖父节点标记为当前节点，继续调整
                    }
                    else { // 叔叔节点为空或为黑色
                        if (x == xp.left) { // 如果当前节点是父节点的左子节点
                            // 以父节点为基准进行右旋，*注意*，这里把x的父节点交给x了，该分支中之后的描述语句主语都是这个新的x。
                            root = rotateRight(root, x = xp);
                            xpp = (xp = x.parent) == null ? null : xp.parent;
                        }
                        if (xp != null) { // 如果父节点不为空
                            xp.red = false; // 将父节点变为黑色
                            if (xpp != null) { // 如果祖父节点不为空
                                xpp.red = true; // 把祖父节点变为红色
                                root = rotateLeft(root, xpp); // 以祖父节点为基准进行左旋
                            }
                        }
                    }
                }
            }
        }

        // 用于删除元素时保持红黑树平衡
        static <K,V> TreeNode<K,V> balanceDeletion(TreeNode<K,V> root,
                                                   TreeNode<K,V> x) {
            for (TreeNode<K,V> xp, xpl, xpr;;) {
                if (x == null || x == root) // 如果关注点是根节点，可以肯定的是它的左右两边都已经平衡了，都比原来少一个黑节点
                    return root;            // 它没有上级，就可以认为整棵树已经平衡
                else if ((xp = x.parent) == null) {
                    x.red = false; // 关注点是通过旋转等操作后得到的新的根节点，为了符合红黑树性质，将其涂黑后结束
                    return x;
                }
                else if (x.red) { // 为什么碰到关注点是红色就直接涂黑并结束遍历？
                    x.red = false; // 因为这个循环每执行一次，关注点都将上移一层，且这个关注点的左右分支都比一开始少一个黑节点，
                    return root;   // 于是只要遇到红色的节点，将其涂黑就刚好补上了这两个分支缺的一个黑节点，整个树再次达到平衡。
                }
                else if ((xpl = xp.left) == x) { // 关注点是父节点的左子节点
                    if ((xpr = xp.right) != null && xpr.red) { // 如果兄弟节点是个红色节点
                        xpr.red = false; // 把兄弟节点涂黑
                        xp.red = true; // 把父节点涂红
                        root = rotateLeft(root, xp); // 将父节点左旋
                        xpr = (xp = x.parent) == null ? null : xp.right; // 由于旋转，兄弟节点更新了
                    }
                    if (xpr == null)
                        x = xp; // 如果没有兄弟节点，则以父节点为关注点继续调整
                    else {
                        TreeNode<K,V> sl = xpr.left, sr = xpr.right;
                        if ((sr == null || !sr.red) &&
                            (sl == null || !sl.red)) { // 如果兄弟节点没有红色的子节点
                            xpr.red = true; // 那么直接把兄弟节点涂红（无论上面那个旋转有没有进行，兄弟节点肯定是黑色的）
                            x = xp; // 这样达成了当前父节点两子树的平衡，但都比原来少一个黑节点，然后继续向上层寻找
                        }
                        else { // 兄弟节点有红色的子节点
                            if (sr == null || !sr.red) { // 兄弟节点有红色的左子节点
                                if (sl != null)          // 把这个红色的左子节点转到右边去
                                    sl.red = false;
                                xpr.red = true;
                                root = rotateRight(root, xpr);
                                xpr = (xp = x.parent) == null ?
                                    null : xp.right; // 由于旋转要调整一下指针
                            }
                            if (xpr != null) { // 经过上面的判断和旋转，兄弟节点的右子节点现在肯定是红色
                                xpr.red = (xp == null) ? false : xp.red; // 把兄弟节点涂成和父节点一样的颜色
                                if ((sr = xpr.right) != null)
                                    sr.red = false; // 把兄弟节点的右子节点涂黑
                            }
                            if (xp != null) { // 把父节点涂黑后左旋，降低一级
                                xp.red = false;
                                root = rotateLeft(root, xp);
                            }
                            /*
                            想一下最后这段为什么这样旋转和涂色？
                            首先是谁提出旋转的需求？是当前的关注点x，因为删除了一个黑色节点，它需要一个黑色节点来补充它路径上的黑色节点数量，
                            但是直接拿黑色节点会破坏别人的平衡，只有碰到兄弟节点或兄弟节点的子节点是红色的，才有操作的空间。
                            其次看一下每个节点在旋转前后的状态：
                            1. x节点：旋转前缺少一个黑色的节点，旋转后由于父节点涂黑了所以这个缺少的节点补上了，但是它到根节点的路径上又多了
                            一个节点，就是它原来的兄弟节点（旋转后变成了它的祖父节点），所以有一个步骤是把它涂成和原父节点一样的颜色。这样确保
                            了这条路径上仅增加了一个黑色节点。
                            2. x的父节点：旋转后原来x的兄弟节点的左子树变成了它的右子树，由于原来x的兄弟节点必为黑色，这棵子树到根节点路径是
                            子树根 -> 黑色的x的兄弟节点 -> 未知颜色的x的父节点 -> ... -> 根节点，现在x的父节点被涂黑，而x的兄弟节点被涂
                            成了这个未知颜色，所以黑节点数量没变，而且路径也没变，只是链路中两个节点调换了位置，仍然保持平衡。
                            3. x的兄弟节点：它原来是黑的，后来被涂成了原来x的父节点的颜色，此外原来它的父节点变成了黑色并成为了它的左子节点，
                            在的新的结构中，往左边看，从根节点经过它到叶子节点的路径没变，还是它和x原来的父节点，颜色也只是他俩互换了一下。重点
                            是它的右子节点，单独看一下。
                            4. x的兄弟节点的右子节点：这个就是刚才一直在找的红色节点。由于原x的父节点和原x的兄弟节点的变色和旋转，导致它到根
                            节点的路径上少了一个黑色节点，解决方法很简单，直接把它变黑，这样所有子树都平衡了，整个树也就平衡了。
                             */
                            x = root;
                        }
                    }
                }
                else { // 完全对称的操作
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
                return false; // t存在prev，但prev的next不是p
            if (tn != null && tn.prev != t)
                return false; // t存在next，但next的prev不是p
            if (tp != null && t != tp.left && t != tp.right)
                return false; // t存在父节点，但父节点的两个子节点都不是p
            if (tl != null && (tl.parent != t || tl.hash > t.hash))
                return false; // t存在左子节点，但左子节点的哈希值大于t的哈希值
            if (tr != null && (tr.parent != t || tr.hash < t.hash))
                return false; // t存在右子节点，但右子节点的哈希值小于t的哈希值
            if (t.red && tl != null && tl.red && tr != null && tr.red)
                return false; // t是红色节点，且它的左右子节点都是红色节点
            if (tl != null && !checkInvariants(tl))
                return false; // 递归检查左半边树
            if (tr != null && !checkInvariants(tr))
                return false; // 递归检查右半边树
            return true; // 没有任何异常
        }
    }

}
