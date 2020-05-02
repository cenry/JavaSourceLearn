# Hashtable中文文档

## 文档翻译

`Hashtable`实现了一个包含键值映射关系的哈希表。任何不为`null`的对象都可以作为一个`key`或`value`。

为了顺利地存储和检索对象，用作键的对象必须实现hashCode方法和equals方法。

`Hashtable`实例有两个重要的参数：初始容量和负载因子。初始容量即哈希表中桶的数量，它仅仅是哈希表被创建时的容量。注意当发生哈希冲突时，一个桶中会存放多个元素，它们会按照顺序被检索。负载因子是一个衡量哈希表填充程度的标尺，它决定了哈希表在放入多少个元素时扩容。初始容量和负载因子只是一个参考，至于何时以及是否调用`rehash`方法取决于具体的实现。

一般情况下，默认的负载因子（.75）在时间和空间成本之间提供了很好的权衡。较高的值会减少空间开销，但会增加查找的时间（这反映在大多数散列表操作中，包括get和put）。

初始容量控制着空间和时间（`rehash`）的平衡，如果把初始容量设置为一个大于预计元素数量乘以负载因子的数字，就不会发生扩容，也不会发生`rehash`，而初始容量太大会浪费空间。当然如果预计有比较多的元素将要放入哈希表，那么设置一个较大的容量会更高效。

使用方式举例：

```java
// 初始化
Hashtable<String, Integer> numbers = new Hashtable<String, Integer>();
numbers.put("one", 1);
numbers.put("two", 2);
numbers.put("three", 3);
// 获取键对应的值
Integer n = numbers.get("two");
if (n != null) {
  System.out.println("two = " + n);
}
```

`iterator`方法返回的迭代器是*fail-fast*的，如果哈希表的结构在迭代器被创建之后发生了改变（除了迭代器自己的移除方法），那么迭代器会抛出一个`ConcurrentModificationException`异常。因此在并发修改的场景下，迭代器会快速失败，而不会继续做有风险的操作。`Hashtable`的键和元素方法返回的枚举没有*fail-fast*机制。

注意*fail-fast*机制不是完全可靠的，它只会尽可能地抛出这个异常，所以不能依赖这个机制来保证并发修改的安全性。

`Hashtable`是同步的，即线程安全的。但是如果没有线程安全的需求，推荐使用`HashMap`，而如果需要一个线程安全且高并发的`Map`实现，则推荐使用`ConcurrentHashMap`来代替`Hashtable`。