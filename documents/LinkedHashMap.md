# LinkedHashMap中文文档

## 文档翻译

`LinkedHashMap`是一个可预测迭代顺序的、包含哈希表和链表的`Map`接口实现。这个实现与`HashMap`的不同之处在于它维护了一个贯穿其所有条目的双链接列表，这个链表定义了迭代顺序，通常是键插入到映射中的顺序（插入顺序）。注意，如果一个键被重复插入到映射中，插入顺序不会受到影响。（对于一个键k和哈希表m，如果`m.put(k, v)`操作的前一刻`m.containsKey(k)`方法返回`true`，那么该操作被成为重复插入。）

该实现让使用者免于`HashMap`和`Hashtable`提供的未指定的、混乱的排序，并且不会与`TreeMap`相关联增加性能消耗。无论一个`Map`原来是怎么实现的，只要用这个方法就可以创建一个与原`Map`顺序相同的副本：

```java
void foo(Map m) {
    Map copy = new LinkedHashMap(m);
    ...
}
```

这个技术可以用在这个场景：一个模块使用了map，先往map中放东西，然后拷贝，然后按照拷贝的顺序来返回结果（用户经常喜欢让一些东西按照放入的顺序取出）。

`LinkedHashMap`提供了一个特殊的构造函数来创建一个链接的哈希表，其迭代的顺序是其条目最近被访问的顺序，从非最近访问到最近访问(访问顺序)。这种哈希表非常适合构建LRU缓存。调用`put`、`putIfAbsent`、`get`、`getOrDefault`、`compute`、`computeIfAbsent`、`computeIfPresent`或`merge`方法都视为对相应条目的访问(假设在调用完成后它仍然存在)，而`replace`方法只会在成功覆盖了值的时候才视为访问。`putAll`方法为指定映射中的每个映射产生一次条目访问，其顺序是由指定映射的条目集迭代器提供的。上面提到的就是全部的会发生“访问”操作。特别说明，对集合视图的操作不会影响它对应的`LinkedHashMap`的迭代顺序。

`removeEldestEntry(Map.Entry)`方法可能被重写，以便在将新映射添加到map时强制执行自动删除过时映射的策略。

该类提供所有可选的映射操作，并允许`null`元素。与`HashMap`一样，它为基本操作(添加、包含和删除)提供了固定时间的性能，假设hash函数正确地将元素分散到各个bucket中。由于维护链表的额外开销，性能可能略低于`HashMap`，但有一个例外：对`LinkedHashMap`的集合视图的迭代需要与映射的大小成比例的时间，而与它的容量无关。`HashMap`的迭代可能更耗费性能，需要的时间与它的容量成比例。

一个`LinkedHashMap`有两个影响其性能的参数：初始容量和负载因子。它们的定义与`HashMap`完全相同。但是，对`LinkedHashMap`来说，选择一个过高的初始容量的惩罚不如`HashMap`严重，因为它的迭代时间不受容量的影响。

**注意，`LinkedHashMap`不是同步的。**如果多个线程同时访问一个`LinkedHashMap`，并且至少有一个线程从结构上修改了该映射，则必须在外部对其进行同步。有一些集合类自身就支持这种同步，但如果自身不支持，就需要用`Collections.synchronizedMap`方法来包装。最好在创建map时就进行包装，以免节外生枝：

```java
Map m = Collections.synchronizedMap(new LinkedHashMap(...));
```

“结构修改”是添加或删除一个或多个映射的任何操作，或者访问触发的迭代顺序改变。在“insertion-ordered”模式下，仅更新key对应的值并不是结构修改。而在“access-ordered”模式下，仅一个get查询就是结构更改。

这个类的所有集合视图方法返回的集合的迭代器方法返回的迭代器都是fail-fast的：如果在迭代器创建后对映射进行了结构修改，则迭代器将抛出`ConcurrentModificationException`，迭代器自己的remove方法除外。因此，在面对并发修改时，迭代器会快速失败。

注意，不能保证迭代器的fail-fast行为，因为通常情况下，在存在不同步的并发修改的情况下，不可能做出任何硬保证。Fail-fast迭代器会尽最大努力的抛出`ConcurrentModificationException`。因此，不能依赖于此异常去编写程序，迭代器的快速失败行为应该只用于检测错误。

此类的所有集合视图方法返回的集合的`spliterator`方法返回的拆分迭代器都是late-binding、fail-fast并额外具有`Spliterator.ORDERED`属性的。

该类是Java集合框架的成员。

这个类的所有集合视图方法返回的集合的spliterator方法返回的Spliterator是从相应集合的迭代器创建的。

## 实现说明

这个类的前一个版本的内部结构有点不同。由于超类HashMap现在对其某些节点使用树结构，因此内部类`LinkedHashMap.Entry`现在被视为中间节点类，也可以转换为树形式。此类的名称LinkedHashMap.Entry在其当前上下文中有多处混淆，但无法更改。因为有其他地方在调用`removeEldestEntry`方法，如果改了名字会导致一些编译错误。

节点类中的更改还需要使用两个字段（head、tail）而不是指向头节点的指针来维护双向链接的before/after列表。这个类以前在访问、插入和删除时也使用不同风格的回调方法。