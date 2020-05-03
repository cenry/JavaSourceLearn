/*
 * Copyright (c) 1997, 2013, Oracle and/or its affiliates. All rights reserved.
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

import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.io.IOException;

/**
 * @param <K> the type of keys maintained by this map
 * @param <V> the type of mapped values
 *
 * @author  Josh Bloch
 * @see     Object#hashCode()
 * @see     Collection
 * @see     Map
 * @see     HashMap
 * @see     TreeMap
 * @see     Hashtable
 * @since   1.4
 */
public class LinkedHashMap<K,V>
    extends HashMap<K,V>
    implements Map<K,V>
{

    /**
     * HashMap.Node的子类，是LinkedHashMap的普通成员。
     */
    static class Entry<K,V> extends HashMap.Node<K,V> {
        Entry<K,V> before, after;
        Entry(int hash, K key, V value, Node<K,V> next) {
            super(hash, key, value, next);
        }
    }

    private static final long serialVersionUID = 3801124242820219131L;

    /**
     * 双向链表的头部（最早插入）
     */
    transient LinkedHashMap.Entry<K,V> head;

    /**
     * 双向链表的尾部（最新插入）
     */
    transient LinkedHashMap.Entry<K,V> tail;

    /**
     * 迭代顺序的模式：true代表access-order(访问顺序)模式，false代表insertion-order(插入顺序)模式。
     *
     * @serial 序列化字段
     */
    final boolean accessOrder;

    // 内部公共方法

    // 把给定的节点p挂在链表尾部
    private void linkNodeLast(LinkedHashMap.Entry<K,V> p) {
        LinkedHashMap.Entry<K,V> last = tail;
        tail = p;
        if (last == null)
            head = p;
        else {
            p.before = last;
            last.after = p;
        }
    }

    // 把src的前后链接给dst
    private void transferLinks(LinkedHashMap.Entry<K,V> src,
                               LinkedHashMap.Entry<K,V> dst) {
        LinkedHashMap.Entry<K,V> b = dst.before = src.before;
        LinkedHashMap.Entry<K,V> a = dst.after = src.after;
        if (b == null)
            head = dst;
        else
            b.after = dst;
        if (a == null)
            tail = dst;
        else
            a.before = dst;
    }

    // 重写HashMap的钩子方法

    void reinitialize() {
        super.reinitialize();
        head = tail = null;
    }

    Node<K,V> newNode(int hash, K key, V value, Node<K,V> e) {
        LinkedHashMap.Entry<K,V> p =
            new LinkedHashMap.Entry<K,V>(hash, key, value, e);
        linkNodeLast(p);
        return p;
    }

    Node<K,V> replacementNode(Node<K,V> p, Node<K,V> next) {
        LinkedHashMap.Entry<K,V> q = (LinkedHashMap.Entry<K,V>)p;
        LinkedHashMap.Entry<K,V> t =
            new LinkedHashMap.Entry<K,V>(q.hash, q.key, q.value, next);
        transferLinks(q, t);
        return t;
    }

    TreeNode<K,V> newTreeNode(int hash, K key, V value, Node<K,V> next) {
        TreeNode<K,V> p = new TreeNode<K,V>(hash, key, value, next);
        linkNodeLast(p);
        return p;
    }

    TreeNode<K,V> replacementTreeNode(Node<K,V> p, Node<K,V> next) {
        LinkedHashMap.Entry<K,V> q = (LinkedHashMap.Entry<K,V>)p;
        TreeNode<K,V> t = new TreeNode<K,V>(q.hash, q.key, q.value, next);
        transferLinks(q, t);
        return t;
    }

    void afterNodeRemoval(Node<K,V> e) { // 删除节点后调整前后节点的after、before指针
        LinkedHashMap.Entry<K,V> p =
            (LinkedHashMap.Entry<K,V>)e, b = p.before, a = p.after;
        p.before = p.after = null;
        if (b == null)
            head = a;
        else
            b.after = a;
        if (a == null)
            tail = b;
        else
            a.before = b;
    }

    void afterNodeInsertion(boolean evict) { // 可能会删除最早插入的节点
        LinkedHashMap.Entry<K,V> first;
        if (evict && (first = head) != null && removeEldestEntry(first)) {
            K key = first.key;
            removeNode(hash(key), key, null, false, true);
        }
    }

    void afterNodeAccess(Node<K,V> e) { // 如果是access-order模式，将最新访问的节点移动到链表尾部
        LinkedHashMap.Entry<K,V> last;
        if (accessOrder && (last = tail) != e) {
            LinkedHashMap.Entry<K,V> p =
                (LinkedHashMap.Entry<K,V>)e, b = p.before, a = p.after;
            p.after = null;
            if (b == null)
                head = a;
            else
                b.after = a;
            if (a != null)
                a.before = b;
            else
                last = b;
            if (last == null)
                head = p;
            else {
                p.before = last;
                last.after = p;
            }
            tail = p;
            ++modCount;
        }
    }

    // 从head开始沿着after指针遍历，比HashMap要方便
    void internalWriteEntries(java.io.ObjectOutputStream s) throws IOException {
        for (LinkedHashMap.Entry<K,V> e = head; e != null; e = e.after) {
            s.writeObject(e.key);
            s.writeObject(e.value);
        }
    }

    /**
     * 使用指定的初始容量和负载因子构造一个空的insertion-ordered模式的LinkedHashMap实例
     *
     * @param  initialCapacity 初始容量
     * @param  loadFactor      负载因子
     * @throws IllegalArgumentException 如果初始容量为负数或负载因子为非正数
     */
    public LinkedHashMap(int initialCapacity, float loadFactor) {
        super(initialCapacity, loadFactor);
        accessOrder = false;
    }

    /**
     * 使用指定的初始容量和默认的负载因子(0.75)构造一个空的insertion-ordered模式的LinkedHashMap实例
     *
     * @param  initialCapacity 初始容量
     * @throws IllegalArgumentException 如果初始容量为负数
     */
    public LinkedHashMap(int initialCapacity) {
        super(initialCapacity);
        accessOrder = false;
    }

    /**
     * 使用默认初始容量(16)和默认的负载因子(0.75)构造一个空的insertion-ordered模式的LinkedHashMap实例
     */
    public LinkedHashMap() {
        super();
        accessOrder = false;
    }

    /**
     * 使用默认初始容量(16)和默认的负载因子(0.75)构造一个空的insertion-ordered模式的LinkedHashMap实例，
     * 然后按照给定map的entrySet()得到的集合的顺序往新建的map中依次插入元素。
     *
     * @param  m 指定的map
     * @throws NullPointerException 如果指定的map是null
     */
    public LinkedHashMap(Map<? extends K, ? extends V> m) {
        super();
        accessOrder = false;
        putMapEntries(m, false);
    }

    /**
     * 按照给定的初始容量、负载因子和排序模式构造一个空的LinkedHashMap实例
     *
     * @param  initialCapacity 初始容量
     * @param  loadFactor      负载因子
     * @param  accessOrder     排序模式 —— true代表access-order, false代表insertion-order
     * @throws IllegalArgumentException 如果初始容量为负数或负载因子为非正数
     */
    public LinkedHashMap(int initialCapacity,
                         float loadFactor,
                         boolean accessOrder) {
        super(initialCapacity, loadFactor);
        this.accessOrder = accessOrder;
    }


    /**
     * 判断是否包含指定的value，由于迭代方式改变所以重写
     */
    public boolean containsValue(Object value) {
        for (LinkedHashMap.Entry<K,V> e = head; e != null; e = e.after) {
            V v = e.value;
            if (v == value || (value != null && value.equals(v)))
                return true;
        }
        return false;
    }

    /**
     * 如果是在access-order模式，则需要将这个最新访问的节点移动到链表尾部
     */
    public V get(Object key) {
        Node<K,V> e;
        if ((e = getNode(hash(key), key)) == null)
            return null;
        if (accessOrder)
            afterNodeAccess(e);
        return e.value;
    }

    /**
     * {@inheritDoc}
     */
    public V getOrDefault(Object key, V defaultValue) {
       Node<K,V> e;
       if ((e = getNode(hash(key), key)) == null)
           return defaultValue;
       if (accessOrder)
           afterNodeAccess(e);
       return e.value;
   }

    /**
     * {@inheritDoc}
     */
    public void clear() {
        super.clear();
        head = tail = null;
    }

    /**
     * 如果需要移除最早放入的那一项，则返回true。这个方法会在put、putAll放入一个新的条目时执行。它提供了在每次添加新条目时删除最老条目的机会。
     * 在map被当作缓存使用时，这个方法非常有用，通过删除最不经常访问的那个条目，以达到减少内存占用的目的。
     *
     * 举例：这样重写方法可以让map中的条目达到100时删除最不常访问的那个条目，让条目数量维持在100。
     * private static final int MAX_ENTRIES = 100;
     * protected boolean removeEldestEntry(Map.Entry eldest) {
     *    return size() > MAX_ENTRIES;
     * }
     *
     * 此方法内部不会以任何方式修改映射，至于传入的这个元素要不要删除，是在此方法返回true或false后map自己去做的。
     * 如果一定要在此方法中直接修改map的结构，那么返回值必须为false（代表map不用再被修改了）。
     * 如果在此方法中直接修改了map结构，然后又返回true，可能会导致一些意想不到的结果。
     *
     * 此方法的重写几乎不会返回false，因为这样就和普通的map一样了，最老的元素不会被删除。
     *
     * @param    eldest 即最早被插入到map的元素，如果这是一个access-ordered模式的map，那就是最不经常被访问的元素。
     *                  如果这个方法返回true，那么eldest就会被移除。如果map在put或putAll方法执行前是空的，那么执行此方法时传入
     *                  的eldest就是刚刚被放入的那个元素，它即是最新的也是最老的。
     * @return   如果最老的元素需要被移除则返回true，否则返回false。
     */
    protected boolean removeEldestEntry(Map.Entry<K,V> eldest) {
        return false;
    }

    /**
     * 返回包含所有key的集合，规则和HashMap中的相同。
     * 要注意的是，和HashMap不同，它的Spliterator分割迭代器是用Spliterators工具类直接创建的，而不是内部类。
     */
    public Set<K> keySet() {
        Set<K> ks = keySet;
        if (ks == null) {
            ks = new LinkedKeySet();
            keySet = ks;
        }
        return ks;
    }

    final class LinkedKeySet extends AbstractSet<K> {
        public final int size()                 { return size; }
        public final void clear()               { LinkedHashMap.this.clear(); }
        public final Iterator<K> iterator() {
            return new LinkedKeyIterator();
        }
        public final boolean contains(Object o) { return containsKey(o); }
        public final boolean remove(Object key) {
            return removeNode(hash(key), key, null, false, true) != null;
        }
        public final Spliterator<K> spliterator()  {
            return Spliterators.spliterator(this, Spliterator.SIZED |
                                            Spliterator.ORDERED |
                                            Spliterator.DISTINCT);
        }
        public final void forEach(Consumer<? super K> action) {
            if (action == null)
                throw new NullPointerException();
            int mc = modCount;
            for (LinkedHashMap.Entry<K,V> e = head; e != null; e = e.after)
                action.accept(e.key);
            if (modCount != mc)
                throw new ConcurrentModificationException();
        }
    }

    /**
     * 返回包含所有value的集合，规则和HashMap中的相同
     * 要注意的是，和HashMap不同，它的Spliterator分割迭代器是用Spliterators工具类直接创建的，而不是内部类。
     */
    public Collection<V> values() {
        Collection<V> vs = values;
        if (vs == null) {
            vs = new LinkedValues();
            values = vs;
        }
        return vs;
    }

    final class LinkedValues extends AbstractCollection<V> {
        public final int size()                 { return size; }
        public final void clear()               { LinkedHashMap.this.clear(); }
        public final Iterator<V> iterator() {
            return new LinkedValueIterator();
        }
        public final boolean contains(Object o) { return containsValue(o); }
        public final Spliterator<V> spliterator() {
            return Spliterators.spliterator(this, Spliterator.SIZED |
                                            Spliterator.ORDERED);
        }
        public final void forEach(Consumer<? super V> action) {
            if (action == null)
                throw new NullPointerException();
            int mc = modCount;
            for (LinkedHashMap.Entry<K,V> e = head; e != null; e = e.after)
                action.accept(e.value);
            if (modCount != mc)
                throw new ConcurrentModificationException();
        }
    }

    /**
     * 返回包含所有Entry的集合，规则和HashMap中的相同
     * 要注意的是，和HashMap不同，它的Spliterator分割迭代器是用Spliterators工具类直接创建的，而不是内部类。
     */
    public Set<Map.Entry<K,V>> entrySet() {
        Set<Map.Entry<K,V>> es;
        return (es = entrySet) == null ? (entrySet = new LinkedEntrySet()) : es;
    }

    final class LinkedEntrySet extends AbstractSet<Map.Entry<K,V>> {
        public final int size()                 { return size; }
        public final void clear()               { LinkedHashMap.this.clear(); }
        public final Iterator<Map.Entry<K,V>> iterator() {
            return new LinkedEntryIterator();
        }
        public final boolean contains(Object o) {
            if (!(o instanceof Map.Entry))
                return false;
            Map.Entry<?,?> e = (Map.Entry<?,?>) o;
            Object key = e.getKey();
            Node<K,V> candidate = getNode(hash(key), key);
            return candidate != null && candidate.equals(e);
        }
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
            return Spliterators.spliterator(this, Spliterator.SIZED |
                                            Spliterator.ORDERED |
                                            Spliterator.DISTINCT);
        }
        public final void forEach(Consumer<? super Map.Entry<K,V>> action) {
            if (action == null)
                throw new NullPointerException();
            int mc = modCount;
            for (LinkedHashMap.Entry<K,V> e = head; e != null; e = e.after)
                action.accept(e);
            if (modCount != mc)
                throw new ConcurrentModificationException();
        }
    }

    // Map接口方法的重写，主要是修改了迭代方式

    public void forEach(BiConsumer<? super K, ? super V> action) {
        if (action == null)
            throw new NullPointerException();
        int mc = modCount;
        for (LinkedHashMap.Entry<K,V> e = head; e != null; e = e.after)
            action.accept(e.key, e.value);
        if (modCount != mc)
            throw new ConcurrentModificationException();
    }

    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        if (function == null)
            throw new NullPointerException();
        int mc = modCount;
        for (LinkedHashMap.Entry<K,V> e = head; e != null; e = e.after)
            e.value = function.apply(e.key, e.value);
        if (modCount != mc)
            throw new ConcurrentModificationException();
    }

    // 自定义迭代器，供视图方法使用，同样是为了更改迭代的方式。

    abstract class LinkedHashIterator {
        LinkedHashMap.Entry<K,V> next;
        LinkedHashMap.Entry<K,V> current;
        int expectedModCount;

        LinkedHashIterator() {
            next = head;
            expectedModCount = modCount;
            current = null;
        }

        public final boolean hasNext() {
            return next != null;
        }

        final LinkedHashMap.Entry<K,V> nextNode() {
            LinkedHashMap.Entry<K,V> e = next;
            if (modCount != expectedModCount)
                throw new ConcurrentModificationException();
            if (e == null)
                throw new NoSuchElementException();
            current = e;
            next = e.after;
            return e;
        }

        public final void remove() {
            Node<K,V> p = current;
            if (p == null)
                throw new IllegalStateException();
            if (modCount != expectedModCount)
                throw new ConcurrentModificationException();
            current = null;
            K key = p.key;
            removeNode(hash(key), key, null, false, false);
            expectedModCount = modCount;
        }
    }

    final class LinkedKeyIterator extends LinkedHashIterator
        implements Iterator<K> {
        public final K next() { return nextNode().getKey(); }
    }

    final class LinkedValueIterator extends LinkedHashIterator
        implements Iterator<V> {
        public final V next() { return nextNode().value; }
    }

    final class LinkedEntryIterator extends LinkedHashIterator
        implements Iterator<Map.Entry<K,V>> {
        public final Map.Entry<K,V> next() { return nextNode(); }
    }


}
