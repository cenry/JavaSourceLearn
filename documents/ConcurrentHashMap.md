# ConcurrentHashMap中文文档

## 文档翻译

一个支持完全并发检索和高并发更新的哈希表。这个类遵循与`Hashtable`相同的函数规范，并且包含与`Hashtable`的每个方法对应的方法的版本。但是，即使所有操作都是线程安全的，检索操作也不需要锁定，并且不会以阻止所有访问的方式锁定整个表。依赖于它的线程安全特性，它可以与`Hashtable`互操作。

检索操作（包括`get`）通常不阻塞，因此可能与更新操作（包括`put`和`remove`）重叠。对于检索操作，它会取最近一次更新操作完全结束所得到的结果进行操作。（更正式地说，给定一个key的更新操作和给定一个key（非`null`）的查询操作时一个happens-before关系。）对于`putAll`和`clear`等聚合操作，并发检索可能只反映插入或删除某些条目。类似地，迭代器、分割迭代器和枚举反映在迭代器/枚举创建时或创建后哈希表的状态。它们不抛出`ConcurrentModificationException`，但是迭代器一次只能被一个线程使用。请记住，包括`size`、`isEmpty`和`containsValue`在内的聚合状态方法的结果通常只有在map没有在其他线程中进行并发更新时才有用。否则，这些方法的结果可能只能达到监测或估计目的，而非程序的实时状态。

当哈希冲突太多时，表将动态扩展，预期平均效果是每个映射占有大约两个存储箱（对应负载因子0.75）。在添加和删除映射时，这个平均值可能会有很大的差异，但总的来说，这是可以接受的时间和空间的权衡。然而，扩容这一操作对于这个或任何其他类型的哈希表可能都是相对缓慢的，如果可能，最好指定初始容量`initialCapacity`。另外一个可选的`loadFactor`构造函数参数提供了定制表容量的进一步方法，它指定了用于计算给定元素数量要分配的空间量的表密度。此外，为了与这个类的以前版本兼容，构造函数可以选择指定一个预期的并发级别作为内部大小调整的附加提示。注意，使用具有相同`hashCode()`的多个键肯定会降低散列表的性能，对于任何类型的散列表都一样。为了改善（相同的哈希值带来的）影响，当键是可比较（实现`Comparable`接口）的时候，会使用键之间的比较顺序来帮助打破这一相等关系。

可以用`newKeySet()`或`newKeySet(int)`创建一个`ConcurrentHashMap`的集合投影，如果只想查询那些有对应的value的条目，可以用`keySet(Object)`。

`ConcurrentHashMap`可以用作可伸缩的频率统计（直方图或多集合），通过`LongAdder`进行初始化。例如，如果要向`ConcurrentHashMap freqs`添加一个计数器，可以用这个方法：

```java
 freqs.computeIfAbsent(k -> new LongAdder()).increment();
```

这个类及其视图和迭代器实现了`Map`和`Iterator`的所有可选方法。

这个类不允许键或值为`null`，这一点和`Hashtable`相同，和`HashMap`不同。

`ConcurrentHashMaps`支持一组连续的和并行的批量操作，这些操作与大多数流方法不同，即使在其他线程同时更新的映射中，也可以安全且合理地应用这些操作，例如，在计算共享注册表中的值的快照摘要时。有三种操作，每种有四种形式，接受带有键、值、条目和(键、值)参数和/或返回值的函数。由于`ConcurrentHashMap`的元素没有以任何特定的方式排序，并且可以在不同的并行执行中以不同的顺序进行处理，因此所提供函数的正确性不应依赖于任何排序，也不应依赖于在计算过程中可能会暂时更改的任何其他对象或值，除了`forEach`操作，其他操作在理想情况下应当是无副作用的。对`Map.Entry`对象的批量操作不支持`setValue`方法。

- `forEach`：对每个元素执行给定的操作。
- `search`：返回对每个元素应用给定函数的第一个可用的非空结果;当找到结果时，跳过进一步的搜索。
- `reduce`：将每个元素按给定方法集合，给定的方法不应依赖于排序。它有五种变体：
  - 普通形式，因为没有指定返回类型，所以没有`(key, value)`为参数的方法。
  - 映射形式，指定一个方法，map中每个条目都会按照该方法的执行结果积累起来。
  - doubles、longs、ints标量形式，使用一个给定的基础值。

这些批量操作接受一个并行阈值参数。如果当前映射大小估计小于给定阈值，则按顺序执行。使用`Long.MAX_VALUE`值可抑制所有并行性。使用值1可以通过将其划分为足够多的子任务来充分利用所有并行计算使用的`ForkJoinPool.commonPool()`，从而获得最大的并行性。通常，我们首先会选择这些极值中的一个，然后在开销和吞吐量之间得到目标性能的平衡值。

接受和/或返回入口参数的方法维护着键值之间的关联。例如，当需要寻找对应最大值的键时，它们可能很有用。注意，可以使用`new AbstractMap.SimpleEntry(k,v)`作为一个纯粹的`Entry`参数。

如果在我们提供的某个方法运行时出现了异常，批量操作可能会即刻结束。在处理此类异常时，请记住，其他并发执行的函数也可能抛出异常，或者说，如果第一个异常没有抛出，它们将会抛出异常。

与顺序形式相比，并行形式的加速很常见，但不能保证。如果并行化计算的底层工作比计算本身的开销更大，那么对小规模映射的并行执行可能比顺序执行更慢。类似地，如果所有处理器都忙于执行不相关的任务，那么并行化可能实际上并不好用。

所有任务方法的所有参数必须是非`null`的。

该类是Java集合框架的成员。

## 概述

设计此哈希表的主要目的是在最小化更新操作对哈希表的占用，以保持并发可读性（通常是`get`方法，也包括迭代器和其他相关方法）。次要目标是保持空间消耗与`java.util.HashMap`相同或更好，并支持利用多线程在空表上高效率地插入初始值。

此映射通常充当binned（bucketed）哈希表，每个键值映射都保存在一个节点中。大多数节点都是具有`hash`、`key`、`value`和`next`字段的基本节点类的实例。然而，存在各种各样的子类：在树结构中的节点使用`TreeNodes`。`TreeBins`持有一组`TreeNodes`的根。

在调整大小期间，转发节点（`ForwardingNodes`）放置在容器的头部。在`computeIfAbsent`和相关方法中建立值时，保留节点（`ReservationNodes`）被用作占位符。`TreeBin`、`ForwardingNode`和`ReservationNode`类型不包含普通的用户键、值或散列，并且在搜索等过程中易于区分，因为它们具有负散列字段和空键和值字段。（这些特殊节点要么不常见，要么是暂时的，因此携带一些未使用的字段的影响是微不足道的。）

表在第一次插入时被惰性地初始化为2的整数次幂大小。表中的每个bin通常包含一个节点列表（通常，该列表只有零个或一个节点）。表访问需要volatile/atomic的读写以及CAS技术。因为在不添加更多间接指令的情况下，没有其他方法可以安排此操作，所以我们使用了`intrinsic`（`sun.misc.Unsafe`）操作。

我们使用节点哈希字段的最高（符号）位进行控制——由于寻址约束，它仍然是可用的。在map方法中，对具有负哈希字段的节点进行特殊处理或忽略。

将第一个节点插入到空的bin时，只需要用CAS，这也是最常见的一种情况。其他更新操作（插入、删除和替换）需要锁定。我们不想浪费空间为每一个bin都加上一个锁，所以直接把每个bin的第一个节点本身作为锁。对这些锁的锁定支持依赖于内置的“synchronized”监视器。

但是，使用列表的第一个节点作为锁本身并不足够：当一个节点被锁定时，任何更新都必须首先验证它锁定后仍然第一个节点，如果不是，则重试。因为新节点总是附加到列表中，所以一旦某个节点位于bin中的第一个节点，它将保持在第一个节点，直到删除或bin变为无效（在调整大小时）。

bin锁的主要缺点是，受同一个锁保护的bin列表中的其他节点上的其他更新操作可能会暂停，例如当user equals（）或映射函数花费很长时间时。然而，经过统计，在随机散列码下，这不是一个常见的问题。理想情况下，容器中的节点遵循泊松分布。泊松分布公式：

```
P{X = k} = (λ ^ k) * (e ^ (-λ)) / (k!)
```

在给定0.75的大小调整阈值的情况下，平均参数约为0.5。即`λ = 0.5`，忽略方差，一个bin中有k个元素的概率分布如下：

0：0.60653066

1：0.30326533

2：0.07581633

3：0.01263606

4：0.00157952

5：0.00015795

6：0.00001316

7：0.00000094

8：0.00000006

更多：不到千万分之一

在随机散列下，两个线程访问不同元素的锁争用概率约为1 / (8 * 元素数量)。

在实践中遇到的实际散列码分布有时明显偏离均匀随机性。这包括N>（1<<30）时的情况，因此某些键必然会发生碰撞。所以有些恶意的使用者将键的哈希值设计为低位相同，而高位又是被屏蔽的，这时就会有大量节点发生哈希冲突。因此在一个bin中节点数量超过一个阈值时，我们会使用一个辅助策略，即将该bin转为`TreeBin`，这些树使用平衡树来保存节点（红黑树的一种特殊形式），将搜索时间限制为O（log N）。`TreeBin`中的每个搜索步骤的速度至少比常规列表中慢2倍，但是N不能超过（1<<64）（在地址用完之前）。只要key是可比较的（这是非常常见的——`String`、`Long`等），那么这将把检索的步数、锁的持有时间限制在一个合理的常数（每个操作最坏情况下大约检查100个节点）。`TreeBin`节点（`TreeNodes`）也保持与常规节点相同的`next`遍历指针，因此可以用相同的方式在迭代器中遍历。

当占用率超过百分比阈值（一般为0.75）时，该表将调整大小。任何注意到bin已满的线程都会帮忙在启动线程分配和设置替换数组后调整大小。但是，其他线程可能会继续插入等操作，而不是暂停。使用`TreeBin`可以避免在进行大小调整时出现最坏的过度填充情况。调整大小是通过将容器从表一个一个地转移到下一个表来进行的。但是，线程在这样做之前会声明要传输的索引块很小（通过字段`transferIndex`），从而减少争用。字段`sizeCtl`中的生成戳可确保大小调整不会重叠。因为我们使用的是2的整数次幂长度的数组，所以每个bin中的元素要么保持在同一索引中，要么以2的整数次幂的偏移量移动。我们通过重用旧节点来消除不必要的节点创建，因为它们的`next`字段不会更改。平均来说，当一个表容量翻倍时，只有六分之一的节点需要被克隆。一旦它们不再被任何可能正在并发遍历表的读取器线程引用，它们替换的节点就可以被垃圾回收。传输时，旧表bin只包含一个特殊的转发节点（哈希字段“MOVED”），该节点包含下一个表作为其键。遇到转发节点时，访问和更新操作需要去新表重新开始。

每个bin传输都需要其bin锁，在调整大小时，该锁可能会暂停等待锁的行为。但是，由于其他线程可以加入并帮助调整大小，而不是争用锁，因此随着调整大小的进行，平均聚合等待时间会变短。传输操作还必须确保新表和旧表中所有可访问的bin都可供任何遍历使用。从最后一个bin（table.length - 1）向上到第一个箱子，部分地进行了排列。当看到一个转发节点时，遍历（参见类`Traverser`）安排移动到新表而不重新访问节点。为了确保即使移动顺序不正确也不会跳过中间的节点，在遍历期间第一次遇到转发节点时创建堆栈（请参阅类`TableStack`），以便在以后处理当前表时保留其位置。对这些保存/恢复机制的需求相对较少，但当遇到一个转发节点时，通常会遇到更多。因此，遍历器使用一个简单的缓存方案来避免创建这么多新的`TableStack`节点。（感谢Peter Levart建议在这里使用堆栈。）

遍历方案还适用于对bin范围的部分遍历（通过备用的`Traverser`构造函数），以支持分区聚合操作。此外，只读操作在转发到空表时会放弃，这一操作为关闭式清除提供支持，而关闭式清除目前未实现。

惰性表初始化在第一次使用之前将占用空间最小化，并且如果第一次操作来自`putAll`，或带`map`参数的构造函数，或反序列化时避免了扩容操作。这些情况下会覆盖设置的初始容量，但不会有什么不好的影响。

元素的数量由专门的`LongAdder`来维护。为了不让所有线程去争用这个计数器，使用了`CounterCell`类，但如果在并发访问期间读取太频繁，则可能会遇到缓存抖动。为了避免如此频繁的读取，只有在添加到已包含两个或多个节点的bin时才尝试争用计数器调整大小。在均匀散列分布下，在阈值处发生这种情况的概率约为13%，这意味着只有约1/8的操作设置了检查阈值（在调整大小后，这样的检查更少）。

`TreeBin`使用一种特殊的比较形式来进行搜索和相关操作（这是我们不能使用现有集合（如`TreeMaps`）的主要原因）。`TreeBin`包含可比较的元素，但也可能包含其他元素，以及不一定可比较的泛型T元素，因此我们不能在它们之间调用`compareTo`。要处理此问题，树主要按哈希值排序，然后按`Comparable.compareTo`顺序排序（如果适用）。在节点上查找时，如果元素不可比较或比较为0，则在绑定散列值的情况下，可能需要同时搜索左右子元素。（这对应于如果所有元素都是不可比较的并且具有绑定散列，则必须进行完整的列表搜索。）在插入时，为了在重新平衡过程中保持总的顺序（或尽可能接近这里所要求的顺序），我们将用`identityhashcode`来打破僵局。红黑平衡代码是从<a href="http://gee.cs.oswego.edu/dl/classes/collections/RBCell.java">jdk之前的集合</a>更新而来的，该集合基于Cormen、Leiserson和Rivest的“算法简介”（CLR）。

`TreeBin`还需要一个额外的锁定机制。虽然即使在更新期间，读操作也始终可以遍历列表，但树遍历不可能，主要是因为树旋转可能会更改根节点和/或其链接。`TreeBin`包括一个依附于主bin同步策略上的简单读写锁定机制：与插入或删除相关的结构调整已经锁定(因此不能与其他写操作发生冲突)，但必须等待正在进行的读操作完成。因为这样的写操作只能有一个，所以我们使用一个简单的方案，使用一个“waiter”字段来阻止写操作。但是读操作不需要阻塞。如果根锁被持有，它们将沿着缓慢的遍历路径（通过`next`指针）进行，（通过下一个指针）进行，直到锁变为可用或列表用尽为止，以先到者为准。这些情况不是很快，而是最大化了总的预期吞吐量。

维护与该类以前版本的API和序列化的兼容性会带来一些奇怪的问题。主要是：我们将未触及但未使用的构造函数参数保留为concurrency级别。我们接受`loadFactor`构造函数参数，但仅将其应用于初始表容量（这是我们唯一可以保证遵守的的一点）。我们还声明了一个未使用的“Segment”类，该类仅在序列化时以最小形式实例化。

另外，仅仅为了与这个类的以前版本兼容，它扩展了`AbstractMap`，即使它的所有方法都被重写了，它也只是一个无用的包袱。

这份文件的内容用一种比较容易读的方式来组织：首先是主要的静态声明和内部通用方法，然后是字段，然后是主要的公共方法（将多个公共方法分解为内部方法），然后是扩容方法、树、遍历器和批量操作方法。