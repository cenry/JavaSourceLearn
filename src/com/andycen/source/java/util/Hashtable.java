/*
 * Copyright (c) 1994, 2017, Oracle and/or its affiliates. All rights reserved.
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

import java.io.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.BiFunction;
import sun.misc.SharedSecrets;

/**
 * @author  Arthur van Hoff
 * @author  Josh Bloch
 * @author  Neal Gafter
 * @see     Object#equals(java.lang.Object)
 * @see     Object#hashCode()
 * @see     Hashtable#rehash()
 * @see     Collection
 * @see     Map
 * @see     HashMap
 * @see     TreeMap
 * @since JDK1.0
 */
public class Hashtable<K,V>
    extends Dictionary<K,V>
    implements Map<K,V>, Cloneable, java.io.Serializable {

    /**
     * 哈希表的数据
     */
    private transient Entry<?,?>[] table;

    /**
     * 哈希表中键值对的数量
     */
    private transient int count;

    /**
     * 扩容门槛，键值对数量达到该值时进行扩容和rehash
     * 计算方式：容量 * 负载因子
     *
     * @serial 表示序列化字段
     */
    private int threshold;

    /**
     * 负载因子
     *
     * @serial 表示序列化字段
     */
    private float loadFactor;

    /**
     * fail-fast机制支持，当数组结构改变时modCount会自增
     */
    private transient int modCount = 0;

    /** 为了互通性，使用来自于JDK 1.0.2的序列化版本号 **/
    private static final long serialVersionUID = 1421746759512286392L;

    /**
     * 用指定的初始容量和负载因子构造一个空的哈希表
     *
     * @param      initialCapacity   初始容量
     * @param      loadFactor        负载因子
     * @exception  IllegalArgumentException  初始容量小于0，或负载因子是非正数
     */
    public Hashtable(int initialCapacity, float loadFactor) {
        if (initialCapacity < 0)
            throw new IllegalArgumentException("Illegal Capacity: "+
                                               initialCapacity);
        if (loadFactor <= 0 || Float.isNaN(loadFactor))
            throw new IllegalArgumentException("Illegal Load: "+loadFactor);

        if (initialCapacity==0)
            initialCapacity = 1;
        this.loadFactor = loadFactor;
        table = new Entry<?,?>[initialCapacity];
        threshold = (int)Math.min(initialCapacity * loadFactor, MAX_ARRAY_SIZE + 1);
    }

    /**
     * 用指定的初始容量和默认的负载因子0.75构造一个空的哈希表
     *
     * @param     initialCapacity   初始容量
     * @exception IllegalArgumentException  初始容量小于0
     */
    public Hashtable(int initialCapacity) {
        this(initialCapacity, 0.75f);
    }

    /**
     * 用默认的初始容量11和默认的负载因子0.75构造一个空的哈希表
     */
    public Hashtable() {
        this(11, 0.75f);
    }

    /**
     * 构造一个包含给定Map中所有元素的哈希表，初始容量会足够容纳这些元素，负载因子使用默认的0.75
     *
     * @param t 指定的Map
     * @throws NullPointerException 指定的Map是null
     * @since   1.2
     */
    public Hashtable(Map<? extends K, ? extends V> t) {
        this(Math.max(2*t.size(), 11), 0.75f);
        putAll(t);
    }

    /**
     * 返回哈希表中key的数量
     *
     * @return  哈希表中key的数量
     */
    public synchronized int size() {
        return count;
    }

    /**
     * 判断哈希表是否不包含键值对
     *
     * @return  如果哈希表为空则返回true，否则返回false
     */
    public synchronized boolean isEmpty() {
        return count == 0;
    }

    /**
     * 返回一个哈希表中的key的枚举
     *
     * @return  一个哈希表中的key的枚举
     * @see     Enumeration
     * @see     #elements()
     * @see     #keySet()
     * @see     Map
     */
    public synchronized Enumeration<K> keys() {
        return this.<K>getEnumeration(KEYS);
    }

    /**
     * 返回一个哈希表中的value的枚举
     *
     * @return  一个哈希表中的value的枚举
     * @see     java.util.Enumeration
     * @see     #keys()
     * @see     #values()
     * @see     Map
     */
    public synchronized Enumeration<V> elements() {
        return this.<V>getEnumeration(VALUES);
    }

    /**
     * 传入一个value，判断哈希表中是否有key对应这个value，这个方法对性能的占用会高于containsKey方法。
     * 注意，此方法在功能上与containsValue(Map接口的一部分)相同。
     *
     * @param      value   需要搜寻的value
     * @return     当且仅当有key对应的value和给定的value相同(equals)时才返回true，否则返回false
     * @exception  NullPointerException  如果给定的value是null
     */
    public synchronized boolean contains(Object value) {
        if (value == null) {
            throw new NullPointerException();
        }

        Entry<?,?> tab[] = table;
        for (int i = tab.length ; i-- > 0 ;) {
            for (Entry<?,?> e = tab[i] ; e != null ; e = e.next) {
                if (e.value.equals(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 方法内部仅调用了contains方法
     *
     * @since 1.2
     */
    public boolean containsValue(Object value) {
        return contains(value);
    }

    /**
     * Tests if the specified object is a key in this hashtable.
     * 判断给定的key是否在哈希表中
     *
     * @param   key   给定的key
     * @return  当且仅当给定的key是哈希表映射关系集合中的一个key(equals判断)时返回true，否则返回false
     * @throws  NullPointerException  给定的key是null
     * @see     #contains(Object)
     */
    public synchronized boolean containsKey(Object key) {
        Entry<?,?> tab[] = table;
        int hash = key.hashCode();
        int index = (hash & 0x7FFFFFFF) % tab.length;
        for (Entry<?,?> e = tab[index] ; e != null ; e = e.next) {
            if ((e.hash == hash) && e.key.equals(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 返回给定的key对应的value，如果没有从映射关系集合中找到给定的key(equals判断)则返回null
     *
     * @param key 给定的key
     * @return 对应的value，如果没找到key则返回null
     * @throws NullPointerException 如果给定的key是null
     * @see     #put(Object, Object)
     */
    @SuppressWarnings("unchecked")
    public synchronized V get(Object key) {
        Entry<?,?> tab[] = table;
        int hash = key.hashCode();
        int index = (hash & 0x7FFFFFFF) % tab.length;
        for (Entry<?,?> e = tab[index] ; e != null ; e = e.next) {
            if ((e.hash == hash) && e.key.equals(key)) {
                return (V)e.value;
            }
        }
        return null;
    }

    /**
     * 可以分配给数组的最大容量。
     * 有些虚拟机会在数组中存储一些额外信息(header words)。
     * 尝试分配更大的容量会抛出OutOfMemoryError: Requested array size exceeds VM limit的异常
     */
    private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;

    /**
     * 为了有效容纳后续进来的元素，增加哈希表的容量并重新组织其内部结构。
     * 这个方法会在哈希表中的key的数量达到当前容量与负载因子的乘积时自动调用
     */
    @SuppressWarnings("unchecked")
    protected void rehash() {
        int oldCapacity = table.length;
        Entry<?,?>[] oldMap = table;

        // 注意溢出
        int newCapacity = (oldCapacity << 1) + 1;
        if (newCapacity - MAX_ARRAY_SIZE > 0) {
            if (oldCapacity == MAX_ARRAY_SIZE)
                // 如果容量原已达到MAX_ARRAY_SIZE则保持原容量
                return;
            newCapacity = MAX_ARRAY_SIZE; // 如果新容量达到了MAX_ARRAY_SIZE，则将新容量设定在该值
        }
        Entry<?,?>[] newMap = new Entry<?,?>[newCapacity];

        modCount++; // 数组结构变化，modCount自增
        threshold = (int)Math.min(newCapacity * loadFactor, MAX_ARRAY_SIZE + 1);
        table = newMap;

        // 重新计算数组下标并将元素放入新的数组
        for (int i = oldCapacity ; i-- > 0 ;) {
            for (Entry<K,V> old = (Entry<K,V>)oldMap[i] ; old != null ; ) {
                Entry<K,V> e = old;
                old = old.next;

                int index = (e.hash & 0x7FFFFFFF) % newCapacity;
                e.next = (Entry<K,V>)newMap[index];
                newMap[index] = e; // 注意发生哈希冲突时，后放入的元素放在链表的头部
            }
        }
    }

    private void addEntry(int hash, K key, V value, int index) {
        modCount++;

        Entry<?,?> tab[] = table;
        if (count >= threshold) {
            // 如果哈希表中的元素个数已达到扩容门槛，则进行扩容
            rehash();

            tab = table;
            hash = key.hashCode();
            index = (hash & 0x7FFFFFFF) % tab.length;
        }

        // 创建一个新的Entry，并将它放在桶的最前面
        @SuppressWarnings("unchecked")
        Entry<K,V> e = (Entry<K,V>) tab[index];
        tab[index] = new Entry<>(hash, key, value, e);
        count++;
    }

    /**
     * 往哈希表中放入键值对，key和value都不能为null
     *
     * @param      key     指定的key
     * @param      value   指定的value
     * @return     指定的key原来对应的value，如果原来没有这个key，则返回null
     * @exception  NullPointerException  如果key或value是null
     * @see     Object#equals(Object)
     * @see     #get(Object)
     */
    public synchronized V put(K key, V value) {
        // 确认value不是null
        if (value == null) {
            throw new NullPointerException();
        }

        // 确认key是否已存在
        Entry<?,?> tab[] = table;
        int hash = key.hashCode();
        int index = (hash & 0x7FFFFFFF) % tab.length;
        @SuppressWarnings("unchecked")
        Entry<K,V> entry = (Entry<K,V>)tab[index];
        for(; entry != null ; entry = entry.next) {
            if ((entry.hash == hash) && entry.key.equals(key)) {
                V old = entry.value;
                entry.value = value;
                return old; // 已存在则更新value并返回旧value
            }
        }

        addEntry(hash, key, value, index);
        return null;
    }

    /**
     * 从哈希表中删除key为指定的key的键值对。如果哈希表中这个key不存在则什么都不做。
     *
     * @param   key   需要移除的key
     * @return  返回给定的key对应的value，如果不存在则返回null
     * @throws  NullPointerException  如果给定的key是null
     */
    public synchronized V remove(Object key) {
        Entry<?,?> tab[] = table;
        int hash = key.hashCode();
        int index = (hash & 0x7FFFFFFF) % tab.length;
        @SuppressWarnings("unchecked")
        Entry<K,V> e = (Entry<K,V>)tab[index];
        for(Entry<K,V> prev = null ; e != null ; prev = e, e = e.next) {
            if ((e.hash == hash) && e.key.equals(key)) {
                modCount++;
                if (prev != null) { // 如果删除的是链表中间的节点，则把被删除节点的前置节点的next指针指向被删除节点的后置节点
                    prev.next = e.next;
                } else { // 如果删除的是链表头部节点，或未组成链表，则把被删除节点的next指针指向的节点上移到桶的最前端
                    tab[index] = e.next;
                }
                count--;
                V oldValue = e.value;
                e.value = null;
                return oldValue; // 返回旧值
            }
        }
        return null;
    }

    /**
     * 从给定的map中拷贝所有的键值对到当前哈希表，如果key相同则会被覆盖value
     *
     * @param t 给定的map
     * @throws NullPointerException 如果给定的map是null
     * @since 1.2
     */
    public synchronized void putAll(Map<? extends K, ? extends V> t) {
        for (Map.Entry<? extends K, ? extends V> e : t.entrySet())
            put(e.getKey(), e.getValue());
    }

    /**
     * 清除哈希表中的所有键值对
     */
    public synchronized void clear() {
        Entry<?,?> tab[] = table;
        modCount++;
        for (int index = tab.length; --index >= 0; )
            tab[index] = null;
        count = 0;
    }

    /**
     * 创建一个当前哈希表的浅拷贝，哈希表的结构会被拷贝，但key和value不会被克隆。这个操作相对来说比较消耗性能。
     *
     * @return  一个当前哈希表的克隆
     */
    public synchronized Object clone() {
        try {
            Hashtable<?,?> t = (Hashtable<?,?>)super.clone();
            t.table = new Entry<?,?>[table.length];
            for (int i = table.length ; i-- > 0 ; ) {
                t.table[i] = (table[i] != null)
                    ? (Entry<?,?>) table[i].clone() : null;
            }
            t.keySet = null;
            t.entrySet = null;
            t.values = null;
            t.modCount = 0;
            return t;
        } catch (CloneNotSupportedException e) {
            // 因为Hashtable是可克隆的，这个异常不应当会发生。
            throw new InternalError(e);
        }
    }

    /**
     * 返回Hashtable的一个字符串表示，内容是所有元素的一个集合，用半角逗号和空格分隔，
     * 每一个元素会以"key.toString() = value.toString()"形式呈现。
     *
     * @return  哈希表的字符串表示
     */
    public synchronized String toString() {
        int max = size() - 1;
        if (max == -1)
            return "{}";

        StringBuilder sb = new StringBuilder();
        Iterator<Map.Entry<K,V>> it = entrySet().iterator();

        sb.append('{');
        for (int i = 0; ; i++) {
            Map.Entry<K,V> e = it.next();
            K key = e.getKey();
            V value = e.getValue();
            sb.append(key   == this ? "(this Map)" : key.toString());
            sb.append('=');
            sb.append(value == this ? "(this Map)" : value.toString());

            if (i == max)
                return sb.append('}').toString();
            sb.append(", ");
        }
    }


    private <T> Enumeration<T> getEnumeration(int type) {
        if (count == 0) {
            return Collections.emptyEnumeration();
        } else {
            return new Enumerator<>(type, false);
        }
    }

    private <T> Iterator<T> getIterator(int type) {
        if (count == 0) {
            return Collections.emptyIterator();
        } else {
            return new Enumerator<>(type, true);
        }
    }

    // 视图

    /**
     * 这几个字段是不同表现形式的哈希表的元素的集合，被首次请求时会按照当前哈希表的结构被初始化
     * 这几个视图是无状态的，所以都不需要被创建多个
     */
    private transient volatile Set<K> keySet;
    private transient volatile Set<Map.Entry<K,V>> entrySet;
    private transient volatile Collection<V> values;

    /**
     * 以Set形式返回map中所有的key。该集合基于map创建，所以map的结构变化都会反映到这个集合中，反之亦然。
     * 如果在该集合迭代时map改变了结构（除了迭代器自己的remove方法），迭代结果会不可靠。
     * 此外，该集合支持元素删除，当通过Iterator.remove、Set.remove、removeAll、retainAll、clear操作删除元素时，
     * map中对应的元素也会被删除。但是它不支持add和addAll操作。
     *
     * @since 1.2
     */
    public Set<K> keySet() {
        if (keySet == null)
            keySet = Collections.synchronizedSet(new KeySet(), this);
        return keySet;
    }

    private class KeySet extends AbstractSet<K> {
        public Iterator<K> iterator() {
            return getIterator(KEYS);
        }
        public int size() {
            return count;
        }
        public boolean contains(Object o) {
            return containsKey(o);
        }
        public boolean remove(Object o) {
            return Hashtable.this.remove(o) != null;
        }
        public void clear() {
            Hashtable.this.clear();
        }
    }

    /**
     * 与keySet()方法类似
     *
     * @since 1.2
     */
    public Set<Map.Entry<K,V>> entrySet() {
        if (entrySet==null)
            entrySet = Collections.synchronizedSet(new EntrySet(), this);
        return entrySet;
    }

    private class EntrySet extends AbstractSet<Map.Entry<K,V>> {
        public Iterator<Map.Entry<K,V>> iterator() {
            return getIterator(ENTRIES);
        }

        public boolean add(Map.Entry<K,V> o) {
            return super.add(o);
        }

        public boolean contains(Object o) {
            if (!(o instanceof Map.Entry))
                return false;
            Map.Entry<?,?> entry = (Map.Entry<?,?>)o;
            Object key = entry.getKey();
            Entry<?,?>[] tab = table;
            int hash = key.hashCode();
            int index = (hash & 0x7FFFFFFF) % tab.length;

            for (Entry<?,?> e = tab[index]; e != null; e = e.next)
                if (e.hash==hash && e.equals(entry))
                    return true;
            return false;
        }

        public boolean remove(Object o) {
            if (!(o instanceof Map.Entry))
                return false;
            Map.Entry<?,?> entry = (Map.Entry<?,?>) o;
            Object key = entry.getKey();
            Entry<?,?>[] tab = table;
            int hash = key.hashCode();
            int index = (hash & 0x7FFFFFFF) % tab.length;

            @SuppressWarnings("unchecked")
            Entry<K,V> e = (Entry<K,V>)tab[index];
            for(Entry<K,V> prev = null; e != null; prev = e, e = e.next) {
                if (e.hash==hash && e.equals(entry)) {
                    modCount++;
                    if (prev != null)
                        prev.next = e.next;
                    else
                        tab[index] = e.next;

                    count--;
                    e.value = null;
                    return true;
                }
            }
            return false;
        }

        public int size() {
            return count;
        }

        public void clear() {
            Hashtable.this.clear();
        }
    }

    /**
     * 与keySet()方法类似
     *
     * @since 1.2
     */
    public Collection<V> values() {
        if (values==null)
            values = Collections.synchronizedCollection(new ValueCollection(),
                                                        this);
        return values;
    }

    private class ValueCollection extends AbstractCollection<V> {
        public Iterator<V> iterator() {
            return getIterator(VALUES);
        }
        public int size() {
            return count;
        }
        public boolean contains(Object o) {
            return containsValue(o);
        }
        public void clear() {
            Hashtable.this.clear();
        }
    }

    // 比较与哈希

    /**
     * 比较给定的对象和当前哈希表是否相同。
     * "相同"指的是双方的key集合相同且每个key对应的value和对方相同。
     *
     * @param  o 比较对象
     * @return 如果比较结果为相同则返回true，否则返回false
     * @see Map#equals(Object)
     * @since 1.2
     */
    public synchronized boolean equals(Object o) {
        if (o == this)
            return true;

        if (!(o instanceof Map))
            return false;
        Map<?,?> t = (Map<?,?>) o;
        if (t.size() != size())
            return false;

        try {
            Iterator<Map.Entry<K,V>> i = entrySet().iterator();
            while (i.hasNext()) {
                Map.Entry<K,V> e = i.next();
                K key = e.getKey();
                V value = e.getValue();
                if (value == null) { // value为null的元素特殊判断，仅对方map也包含对应的key且value为null时判为该元素相同
                    if (!(t.get(key)==null && t.containsKey(key)))
                        return false;
                } else { // value不为null时，正常拿key取对方的value进行判断
                    if (!value.equals(t.get(key)))
                        return false;
                }
            }
        } catch (ClassCastException unused)   {
            return false;
        } catch (NullPointerException unused) {
            return false;
        }

        return true;
    }

    /**
     * 根据哈希表中的所有元素计算出哈希表自身的哈希值
     *
     * @see Map#hashCode()
     * @since 1.2
     */
    public synchronized int hashCode() {
        /*
         * 如果哈希表的键值对的值是它自己，计算哈希值会陷入死循环，为了防止这种情况的发生，
         * 开始计算哈希值时会把loadFactor置为-loadFactor，此时当下一个hashCode()调用来的时候直接返回结果0，
         * 计算结束后把loadFactor置回原值。
         */
        int h = 0;
        if (count == 0 || loadFactor < 0)
            return h;  // Returns zero

        loadFactor = -loadFactor;  // 将loadFactor置反表示哈希值计算中
        Entry<?,?>[] tab = table;
        for (Entry<?,?> entry : tab) { // 简单地计算所有元素的哈希值之和
            while (entry != null) {
                h += entry.hashCode();
                entry = entry.next;
            }
        }

        loadFactor = -loadFactor;  // 将loadFactor置反表示哈希值计算已完成

        return h;
    }

    @Override
    public synchronized V getOrDefault(Object key, V defaultValue) {
        V result = get(key);
        return (null == result) ? defaultValue : result;
    }

    @SuppressWarnings("unchecked")
    @Override
    public synchronized void forEach(BiConsumer<? super K, ? super V> action) {
        Objects.requireNonNull(action); // 明确地确认action是否为null，否则如果哈希表是空的，这个null就不会被发现

        final int expectedModCount = modCount;

        Entry<?, ?>[] tab = table;
        for (Entry<?, ?> entry : tab) {
            while (entry != null) {
                action.accept((K)entry.key, (V)entry.value);
                entry = entry.next;

                if (expectedModCount != modCount) {
                    throw new ConcurrentModificationException();
                }
            }
        }
    }

    /**
     * 遍历map，以key和value为入参执行传入的方法，将方法的返回值作为新的value覆盖原来的value
     */
    @SuppressWarnings("unchecked")
    @Override
    public synchronized void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        Objects.requireNonNull(function); // 同forEach方法

        final int expectedModCount = modCount;

        Entry<K, V>[] tab = (Entry<K, V>[])table;
        for (Entry<K, V> entry : tab) {
            while (entry != null) {
                entry.value = Objects.requireNonNull(
                    function.apply(entry.key, entry.value));
                entry = entry.next;

                if (expectedModCount != modCount) {
                    throw new ConcurrentModificationException();
                }
            }
        }
    }

    /**
     * 如果map中已经存在这个key且value不为null，则不改变值，返回值是这个key对应的值。
     * 如果map中不存在这个key或value为null，则插入键值对或更新value，返回null。
     */
    @Override
    public synchronized V putIfAbsent(K key, V value) {
        Objects.requireNonNull(value);

        // 确认key是否已存在在哈希表中
        Entry<?,?> tab[] = table;
        int hash = key.hashCode();
        int index = (hash & 0x7FFFFFFF) % tab.length;
        @SuppressWarnings("unchecked")
        Entry<K,V> entry = (Entry<K,V>)tab[index];
        for (; entry != null; entry = entry.next) {
            if ((entry.hash == hash) && entry.key.equals(key)) {
                V old = entry.value;
                if (old == null) {
                    entry.value = value;
                }
                return old;
            }
        }

        addEntry(hash, key, value, index);
        return null;
    }

    /**
     * 只有key和value都对上时才删除元素，如果有元素被删除则返回true。
     */
    @Override
    public synchronized boolean remove(Object key, Object value) {
        Objects.requireNonNull(value);

        Entry<?,?> tab[] = table;
        int hash = key.hashCode();
        int index = (hash & 0x7FFFFFFF) % tab.length;
        @SuppressWarnings("unchecked")
        Entry<K,V> e = (Entry<K,V>)tab[index];
        for (Entry<K,V> prev = null; e != null; prev = e, e = e.next) {
            if ((e.hash == hash) && e.key.equals(key) && e.value.equals(value)) {
                modCount++;
                if (prev != null) {
                    prev.next = e.next;
                } else {
                    tab[index] = e.next;
                }
                count--;
                e.value = null;
                return true;
            }
        }
        return false;
    }

    /**
     * 如果能找到key和value都相同的元素，则把旧的value替换为新的value。如果替换成功返回true。
     */
    @Override
    public synchronized boolean replace(K key, V oldValue, V newValue) {
        Objects.requireNonNull(oldValue);
        Objects.requireNonNull(newValue);
        Entry<?,?> tab[] = table;
        int hash = key.hashCode();
        int index = (hash & 0x7FFFFFFF) % tab.length;
        @SuppressWarnings("unchecked")
        Entry<K,V> e = (Entry<K,V>)tab[index];
        for (; e != null; e = e.next) {
            if ((e.hash == hash) && e.key.equals(key)) {
                if (e.value.equals(oldValue)) {
                    e.value = newValue;
                    return true;
                } else {
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * 如果能找到key相同的元素，则把旧的value替换为新的value。返回旧的value
     */
    @Override
    public synchronized V replace(K key, V value) {
        Objects.requireNonNull(value);
        Entry<?,?> tab[] = table;
        int hash = key.hashCode();
        int index = (hash & 0x7FFFFFFF) % tab.length;
        @SuppressWarnings("unchecked")
        Entry<K,V> e = (Entry<K,V>)tab[index];
        for (; e != null; e = e.next) {
            if ((e.hash == hash) && e.key.equals(key)) {
                V oldValue = e.value;
                e.value = value;
                return oldValue;
            }
        }
        return null;
    }

    /**
     * 如果原来map中原来就存在这个key，就直接返回旧值。
     * 否则以key为入参执行mappingFunction方法，其返回值作为key对应的value插入键值对
     */
    @Override
    public synchronized V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        Objects.requireNonNull(mappingFunction);

        Entry<?,?> tab[] = table;
        int hash = key.hashCode();
        int index = (hash & 0x7FFFFFFF) % tab.length;
        @SuppressWarnings("unchecked")
        Entry<K,V> e = (Entry<K,V>)tab[index];
        for (; e != null; e = e.next) {
            if (e.hash == hash && e.key.equals(key)) {
                // Hashtable不接受null值
                return e.value;
            }
        }

        V newValue = mappingFunction.apply(key);
        if (newValue != null) {
            addEntry(hash, key, newValue, index);
        }

        return newValue;
    }

    /**
     * 如果原来map中不存在这个key，则返回null。如果原来存在，则以key和value为入参执行remappingFunction方法，
     * 如果方法的返回值为null，则删除该元素，否则以返回值替换旧值，最终方法的返回值为remappingFunction方法的返回值。
     */
    @Override
    public synchronized V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(remappingFunction);

        Entry<?,?> tab[] = table;
        int hash = key.hashCode();
        int index = (hash & 0x7FFFFFFF) % tab.length;
        @SuppressWarnings("unchecked")
        Entry<K,V> e = (Entry<K,V>)tab[index];
        for (Entry<K,V> prev = null; e != null; prev = e, e = e.next) {
            if (e.hash == hash && e.key.equals(key)) {
                V newValue = remappingFunction.apply(key, e.value);
                if (newValue == null) {
                    modCount++;
                    if (prev != null) {
                        prev.next = e.next;
                    } else {
                        tab[index] = e.next;
                    }
                    count--;
                } else {
                    e.value = newValue;
                }
                return newValue;
            }
        }
        return null;
    }

    /**
     * 如果原来key存在于map中，则以key和value为入参执行remappingFunction，结果如果是null则删除该节点，否则更新value，返回计算结果。
     * 如果原来key不存在于map中，则以key和null为入参执行remappingFunction，结果如果不为null则以计算结果为value插入节点，返回计算结果。
     */
    @Override
    public synchronized V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(remappingFunction);

        Entry<?,?> tab[] = table;
        int hash = key.hashCode();
        int index = (hash & 0x7FFFFFFF) % tab.length;
        @SuppressWarnings("unchecked")
        Entry<K,V> e = (Entry<K,V>)tab[index];
        for (Entry<K,V> prev = null; e != null; prev = e, e = e.next) {
            if (e.hash == hash && Objects.equals(e.key, key)) {
                V newValue = remappingFunction.apply(key, e.value);
                if (newValue == null) {
                    modCount++;
                    if (prev != null) {
                        prev.next = e.next;
                    } else {
                        tab[index] = e.next;
                    }
                    count--;
                } else {
                    e.value = newValue;
                }
                return newValue;
            }
        }

        V newValue = remappingFunction.apply(key, null);
        if (newValue != null) {
            addEntry(hash, key, newValue, index);
        }

        return newValue;
    }

    /**
     * 如果原来key存在于map中，则以原value和传入的value为入参执行remappingFunction，
     * 如果返回结果为null则删除节点，否则更新value，并返回计算结果。
     *
     * 如果原来key不存在于map中，则直接以传入的key和value组成节点插入到哈希表
     */
    @Override
    public synchronized V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(remappingFunction);

        Entry<?,?> tab[] = table;
        int hash = key.hashCode();
        int index = (hash & 0x7FFFFFFF) % tab.length;
        @SuppressWarnings("unchecked")
        Entry<K,V> e = (Entry<K,V>)tab[index];
        for (Entry<K,V> prev = null; e != null; prev = e, e = e.next) {
            if (e.hash == hash && e.key.equals(key)) {
                V newValue = remappingFunction.apply(e.value, value);
                if (newValue == null) {
                    modCount++;
                    if (prev != null) {
                        prev.next = e.next;
                    } else {
                        tab[index] = e.next;
                    }
                    count--;
                } else {
                    e.value = newValue;
                }
                return newValue;
            }
        }

        if (value != null) {
            addEntry(hash, key, value, index);
        }

        return value;
    }

    /**
     * 将Hashtable的状态保存到一个数据流中（即序列化）
     *
     * 序列化内容如下
     * @serialData 容量（桶数组的长度），接着是size（键值映射对的数量），然后是每个键值对的key和value（没有特定的顺序）
     */
    private void writeObject(java.io.ObjectOutputStream s)
            throws IOException {
        Entry<Object, Object> entryStack = null;

        synchronized (this) {
            // threshold、loadFactor没有transient修饰，会被自动写入流
            s.defaultWriteObject();

            // 写入当前的容量和键值对数量
            s.writeInt(table.length);
            s.writeInt(count);

            // 数组中所有条目的堆栈副本
            for (int index = 0; index < table.length; index++) {
                Entry<?,?> entry = table[index];

                while (entry != null) {
                    entryStack =
                        new Entry<>(0, entry.key, entry.value, entryStack);
                    entry = entry.next;
                }
            }
        }

        // 从键值对的堆栈副本中写key/value对象到数据流中
        while (entryStack != null) {
            s.writeObject(entryStack.key);
            s.writeObject(entryStack.value);
            entryStack = entryStack.next;
        }
    }

    /**
     * 根据数据流重建Hashtable（即反序列化）
     */
    private void readObject(java.io.ObjectInputStream s)
         throws IOException, ClassNotFoundException
    {
        // 读取threshold和loadFactor
        s.defaultReadObject();

        // 校验loadFactor（忽略threshold，因为它会被重新计算）
        if (loadFactor <= 0 || Float.isNaN(loadFactor))
            throw new StreamCorruptedException("Illegal Load: " + loadFactor);

        // 读取数组容量和键值对数量
        int origlength = s.readInt();
        int elements = s.readInt();

        // 校验键值对数量
        if (elements < 0)
            throw new StreamCorruptedException("Illegal # of Elements: " + elements);

        // 确保读取到的数组容量比键值对数量除以负载因子的结果要大
        origlength = Math.max(origlength, (int)(elements / loadFactor) + 1);

        // 将原容量扩大5% + 3，但不超过从数据流中读取到的数组容量，如果溢出了则还是按照读取到的数组容量做后续操作
        int length = (int)((elements + elements / 20) / loadFactor) + 3;
        if (length > elements && (length & 1) == 0)
            length--;
        length = Math.min(length, origlength);

        if (length < 0) { // overflow
            length = origlength;
        }

        // 用Map.Entry[].class做校验，因为它是我们创建的最接近的类型
        SharedSecrets.getJavaOISAccess().checkArray(s, Map.Entry[].class, length);
        table = new Entry<?,?>[length];
        threshold = (int)Math.min(length * loadFactor, MAX_ARRAY_SIZE + 1);
        count = 0;

        // 读取所有的key和value，放到新的map中
        for (; elements > 0; elements--) {
            @SuppressWarnings("unchecked")
                K key = (K)s.readObject();
            @SuppressWarnings("unchecked")
                V value = (V)s.readObject();
            // 为了提高性能，取消了同步
            reconstitutionPut(table, key, value);
        }
    }

    /**
     * readObject使用的put方法。put是可重写的，不应该在readObject中调用它，因为此时子类还没有被初始化。
     *
     * 这与常规的put方法有几个不同之处。
     * 由于表中初始元素的数量是已知的，所以不需要检查是否需要rehash。
     * modCount没有增加，也没有同步，因为我们正在创建一个新的实例。而且不需要返回值。
     */
    private void reconstitutionPut(Entry<?,?>[] tab, K key, V value)
        throws StreamCorruptedException
    {
        if (value == null) {
            throw new java.io.StreamCorruptedException();
        }
        // 确保key不存在于哈希表，这在反序列化时应该不会发生。
        int hash = key.hashCode();
        int index = (hash & 0x7FFFFFFF) % tab.length;
        for (Entry<?,?> e = tab[index] ; e != null ; e = e.next) {
            if ((e.hash == hash) && e.key.equals(key)) {
                throw new java.io.StreamCorruptedException();
            }
        }
        // 创建新的Entry
        @SuppressWarnings("unchecked")
            Entry<K,V> e = (Entry<K,V>)tab[index];
        tab[index] = new Entry<>(hash, key, value, e);
        count++;
    }

    /**
     * 代表Hashtable中的元素的类
     */
    private static class Entry<K,V> implements Map.Entry<K,V> {
        final int hash; // 这个hash值直接就是key的hashCode()结果
        final K key;
        V value;
        Entry<K,V> next;

        protected Entry(int hash, K key, V value, Entry<K,V> next) {
            this.hash = hash;
            this.key =  key;
            this.value = value;
            this.next = next;
        }

        @SuppressWarnings("unchecked")
        protected Object clone() {
            return new Entry<>(hash, key, value,
                                  (next==null ? null : (Entry<K,V>) next.clone()));
        }

        // Map.Entry Ops

        public K getKey() {
            return key;
        }

        public V getValue() {
            return value;
        }

        public V setValue(V value) {
            if (value == null)
                throw new NullPointerException();

            V oldValue = this.value;
            this.value = value;
            return oldValue;
        }

        public boolean equals(Object o) {
            if (!(o instanceof Map.Entry))
                return false;
            Map.Entry<?,?> e = (Map.Entry<?,?>)o;

            return (key==null ? e.getKey()==null : key.equals(e.getKey())) &&
               (value==null ? e.getValue()==null : value.equals(e.getValue()));
        }

        public int hashCode() {
            return hash ^ Objects.hashCode(value);
        }

        public String toString() {
            return key.toString()+"="+value.toString();
        }
    }

    // 枚举或迭代器的类型
    private static final int KEYS = 0;
    private static final int VALUES = 1;
    private static final int ENTRIES = 2;

    /**
     * 一个哈希表内部用的枚举类，它同时实现了Enumeration和Iterator接口，但是可以在禁用迭代器的情况下创建单个实例，
     * 这样可以避免提供无意义的功能给只想使用枚举的用户。
     */
    private class Enumerator<T> implements Enumeration<T>, Iterator<T> {
        Entry<?,?>[] table = Hashtable.this.table;
        int index = table.length;
        Entry<?,?> entry;
        Entry<?,?> lastReturned;
        int type;

        /**
         * 表明一个Enumerator实例被当作是迭代器还是枚举。true对应迭代器
         */
        boolean iterator;

        /**
         * fail-fast支持
         */
        protected int expectedModCount = modCount;

        Enumerator(int type, boolean iterator) {
            this.type = type;
            this.iterator = iterator;
        }

        public boolean hasMoreElements() {
            Entry<?,?> e = entry;
            int i = index;
            Entry<?,?>[] t = table;
            /* 使用局部变量加快循环迭代 */
            while (e == null && i > 0) {
                e = t[--i];
            }
            entry = e;
            index = i;
            return e != null;
        }

        @SuppressWarnings("unchecked")
        public T nextElement() {
            Entry<?,?> et = entry;
            int i = index;
            Entry<?,?>[] t = table;
            /* 使用局部变量加快循环迭代 */
            while (et == null && i > 0) {
                et = t[--i];
            }
            entry = et;
            index = i;
            if (et != null) {
                Entry<?,?> e = lastReturned = entry;
                entry = e.next;
                return type == KEYS ? (T)e.key : (type == VALUES ? (T)e.value : (T)e);
            }
            throw new NoSuchElementException("Hashtable Enumerator");
        }

        // 迭代器方法
        public boolean hasNext() {
            return hasMoreElements();
        }

        public T next() {
            if (modCount != expectedModCount)
                throw new ConcurrentModificationException();
            return nextElement();
        }

        public void remove() {
            if (!iterator)
                throw new UnsupportedOperationException();
            if (lastReturned == null)
                throw new IllegalStateException("Hashtable Enumerator");
            if (modCount != expectedModCount)
                throw new ConcurrentModificationException();

            synchronized(Hashtable.this) {
                Entry<?,?>[] tab = Hashtable.this.table;
                int index = (lastReturned.hash & 0x7FFFFFFF) % tab.length;

                @SuppressWarnings("unchecked")
                Entry<K,V> e = (Entry<K,V>)tab[index];
                for(Entry<K,V> prev = null; e != null; prev = e, e = e.next) {
                    if (e == lastReturned) {
                        modCount++;
                        expectedModCount++;
                        if (prev == null)
                            tab[index] = e.next;
                        else
                            prev.next = e.next;
                        count--;
                        lastReturned = null;
                        return;
                    }
                }
                throw new ConcurrentModificationException();
            }
        }
    }
}
