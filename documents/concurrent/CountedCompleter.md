# CountedCompleter中文文档

## 文档翻译

`ForkJoinTask`的一个子类，在没有剩余的等待任务时会触发一个方法执行。和其他`ForkJoinTask`的子类相比，`CountedCompleter`在应对子任务阻塞时会有更好的稳定性，但在编码上不是那么直观。和其他同类组件（例如`CompletionHandler`）相比，`CountedCompleter`有一个特殊的地方：完成了多个等待的任务后会触发`onCompletion(CountedCompleter)`，而且不止一个。除非另外初始化，否则等待任务的计数从0开始，但是可以使用`setPendingCount(int)`、`addToPendingCount(int)`和`compareAndSetPendingCount(int, int)`方法(原子性地)更改。在调用`tryComplete()`时，如果计数为非零，则递减;否则，将执行completion动作，如果这个完井器本身有一个completion，则继续执行它的completion动作。与相关的同步组件(例如Phaser和Semaphore)一样，这些方法只影响内部计数;他们没有建立任何进一步的内部记录。特别的，未处理的任务的标识没有得到维护，您可以创建子类来记录某些或所有挂起的任务或它们在需要时的结果。本类还提供了支持自定义遍历completion公共方法。但是，因为`CountedCompleter`只提供了基本的同步机制，所以开发者可以创建进一步的抽象子类来扩展它的功能。

具体的`CountedCompleter`类必须定义`compute()`方法，在大多数情况下(如下所示)，在返回之前应该调用`tryComplete()`一次。该类还可以选择性地覆盖方法`onCompletion(CountedCompleter)`来在正常完成时执行一个动作，以及方法`onExceptionalCompletion(Throwable, CountedCompleter)`来在任何异常时执行一个动作。

`CountedCompleter`通常不承担执行结果，在这种情况下，它们通常被声明为`CountedCompleter<Void>`，并且总是返回`null`作为结果值。在其他情况下，您应该覆盖方法`getRawResult()`来提供来自`join()`、`invoke()`和相关方法的结果。通常，该方法应该返回`CountedCompleter`对象的一个字段(或一个或多个字段的函数)的值，该对象在完成时保存结果。默认情况下，`setRawResult(T)`方法在`CountedCompleter`中不起作用。重写此方法以维护包含结果数据的其他对象或字段也是可以的，但很少有情况适用。

一个`CountedCompleter`本身没有completer（即`getCompleter()`返回值为`null`）。但是，任何有另一个completer的completer只能作为其他计算的内部帮助者，因此它自己的任务状态（如`ForkJoinTask.isDone`等方法中所报告的）是不确定的，这个状态仅在显式调用`complete(T)`、`ForkJoinTask.cancel(boolean)`、`ForkJoinTask.completeExceptionally(Throwable)`或在任务异常地完成时更改。任何一次异常地任务完成任务，都有可能把异常转移给任务的completer，以及completer的completer（如果completer存在并且还没完成），等等。类似地，取消内部`CountedCompleter`只对该completer有局部影响，因此通常没有用。

## 使用示例

### 并行递归分解

`CountedCompleter`可能会像`RecursiveAction`那样组织在树结构中，尽管它们的构造往往不相同。在这里，每个任务的completer是计算树中的父任务。尽管需要更多计算空间，当我们需要对一个集合的所有元素做一些可能比较耗费时间的任务（任务可能可以进一步分解）时，`CountedCompleter`仍然是一个比较好的选择，尤其是因为内部原因（如I/O）或其他原因（例如垃圾收集）导致这个操作相比普通的操作（例如元素之间相互比较）相比有明显的不同时。因为`CountedCompleter`提供了自己的延续，所以其他线程不需要阻塞等待执行它们。

例如，这里是一个类的初始版本，它使用“除以2”递归分解将工作分成单个部分（叶子任务）。即使工作被分割成单独的调用，基于树的技术通常比直接分叉叶任务更可取，因为它们减少了线程间的通信并改进了负载平衡。在递归情况下，每一对子任务中的第二个子任务完成将触发其父任务的完成（因为不执行结果组合，所以不会重写方法onCompletion的默认no-op实现）。一个静态方法设置了基本任务并且执行（这里隐式地使用`ForkJoinPool.commonPool()`）。

```java
class MyOperation<E> { void apply(E e) { ... }  }

class ForEach<E> extends CountedCompleter<Void> {
    public static <E> void forEach(E[] array, MyOperation<E> op) {
        new ForEach<E>(null, array, op, 0, array.length).invoke();
    }

    final E[] array; final MyOperation<E> op; final int lo, hi;
   	ForEach(CountedCompleter<?> p, E[] array, MyOperation<E> op, int lo, int hi) {
   	    super(p);
        this.array = array; this.op = op; this.lo = lo; this.hi = hi;
    }

    public void compute() { // version 1
        if (hi - lo >= 2) {
            int mid = (lo + hi) >>> 1;
            setPendingCount(2); // must set pending count before fork
            new ForEach(this, array, op, mid, hi).fork(); // right child
            new ForEach(this, array, op, lo, mid).fork(); // left child
        }
        else if (hi > lo)
            op.apply(array[lo]);
        tryComplete();
    }
}
```

可以注意到，在递归情况下，任务在分叉它的右任务之后什么也不做，因此可以在返回之前直接调用它的左任务，从而改进这种设计。(这类似于尾部递归移除。)此外，由于任务在执行其左任务时返回(而不是通过调用tryComplete)，挂起计数被设置为1:

```java
public void compute() { // version 2
    if (hi - lo >= 2) {
        int mid = (lo + hi) >>> 1;
        setPendingCount(1); // only one pending
        new ForEachV2<>(this, array, op, mid, hi).fork(); // right child
        new ForEachV2<>(this, array, op, lo, mid).compute(); // direct invoke
    } else {
        if (hi > lo)
            op.apply(array[lo]);
        tryComplete();
    }
}
```

作为进一步的改进，请注意左边的任务甚至不需要存在。我们可以使用原始任务进行迭代，并为每个fork添加一个挂起计数，而不是创建一个新任务。此外，由于此树中的任何任务都没有实现`onCompletion(CountedCompleter)`方法，所以可以将`tryComplete()`替换为`propagateCompletion()`。

```java
public void compute() { // version 3
    int l = lo,  h = hi;
    while (h - l >= 2) {
        int mid = (l + h) >>> 1;
        addToPendingCount(1);
        new ForEachV3<>(this, array, op, mid, h).fork(); // right child
        h = mid;
    }
    if (h > l)
        op.apply(array[l]);
    propagateCompletion();
}
```

这个类的进一步改进可能需要预先计算挂起任务的数量，以便在构造函数中建立它们，为叶子步骤设定专门的类，每次迭代细分4个类，而不是2个，并使用自适应阈值，而不是总是细分为单个元素。

### 查找

`CountedCompleter`树可以在数据结构的不同部分搜索值或属性，并在查找到的时候以`AtomicReference`形式报告结果。其他线程可以获取到查找的结果以避免不必要的操作。(您还可以取消其他任务，但通常更简单、更有效的方法是让他们注意到设置了结果，如果它们发现已经设置了结果，则会跳过进一步的处理。)

```java
class Searcher<E> extends CountedCompleter<E> {
    final E[] array; final AtomicReference<E> result; final int lo, hi;
    Searcher(CountedCompleter<?> p, E[] array, AtomicReference<E> result, int lo, int hi) {
        super(p);
        this.array = array; this.result = result; this.lo = lo; this.hi = hi;
    }
    public E getRawResult() { return result.get(); }
    public void compute() { // similar to ForEach version 3
        int l = lo,  h = hi;
        while (result.get() == null && h >= l) {
            if (h - l >= 2) {
                int mid = (l + h) >>> 1;
                addToPendingCount(1);
                new Searcher(this, array, result, mid, h).fork();
                h = mid;
            }
            else {
                E x = array[l];
                if (matches(x) && result.compareAndSet(null, x))
                    quietlyCompleteRoot(); // root task is now joinable
                break;
            }
        }
        tryComplete(); // normally complete whether or not found
    }
    boolean matches(E e) { ... } // return true if found

    public static <E> E search(E[] array) {
        return new Searcher<E>(null, array, new AtomicReference<E>(), 0, array.length).invoke();
    }
 }
```

在这个例子中，所有任务的目标只有`compareAndSet`，一旦找到了要找的元素，并且`result`被设了置，就没有继续执行的必要了。

### 记录子任务

将多个子任务的结果组合在一起的`CountedCompleter`任务通常需要在方法`onCompletion(CountedCompleter`)中访问这些结果。如下面的类(它执行简化的map-reduce，其中映射和reduce方法都是E类型的)所示，在分而治之的设计中实现这一点的一种方法是让每个子任务记录它的兄弟任务，这样就可以在方法`onCompletion`中访问它。这种方法适用于左右结果顺序无关紧要的reduce，因为有序的reduce需要明确的左/右名称。在上述例子中看到的优化在这个例子中也可以应用。

```java
class MyMapper<E> { E apply(E v) {  ...  } }
class MyReducer<E> { E apply(E x, E y) {  ...  } }
class MapReducer<E> extends CountedCompleter<E> {
    final E[] array; final MyMapper<E> mapper;
    final MyReducer<E> reducer; final int lo, hi;
    MapReducer<E> sibling;
    E result;
    MapReducer(CountedCompleter<?> p, E[] array, MyMapper<E> mapper,
               MyReducer<E> reducer, int lo, int hi) {
        super(p);
        this.array = array; this.mapper = mapper;
        this.reducer = reducer; this.lo = lo; this.hi = hi;
    }
    public void compute() {
        if (hi - lo >= 2) {
            int mid = (lo + hi) >>> 1;
            MapReducer<E> left = new MapReducer(this, array, mapper, reducer, lo, mid);
            MapReducer<E> right = new MapReducer(this, array, mapper, reducer, mid, hi);
            left.sibling = right;
            right.sibling = left;
            setPendingCount(1); // only right is pending
            right.fork();
            left.compute();     // directly execute left
        }
        else {
            if (hi > lo)
                result = mapper.apply(array[lo]);
            tryComplete();
        }
    }
    public void onCompletion(CountedCompleter<?> caller) {
        if (caller != this) {
            MapReducer<E> child = (MapReducer<E>)caller;
            MapReducer<E> sib = child.sibling;
            if (sib == null || sib.result == null)
                result = child.result;
            else
                result = reducer.apply(child.result, sib.result);
        }
    }
    public E getRawResult() { return result; }

    public static <E> E mapReduce(E[] array, MyMapper<E> mapper, MyReducer<E> reducer) {
        return new MapReducer<E>(null, array, mapper, reducer,
                                 0, array.length).invoke();
    }
}
```

方法`onCompletion`中对结果进行了聚合。每当pending的数量减为0的时候，都会将这个任务的两个子任务的计算结果聚合到当前任务中，并再向上聚合。如果caller是自己则不需要任何操作。否则caller是当前任务的两个子任务之一，将它和它的兄弟任务结果聚合。每一个任务结算的前提都是它和它的子任务都已经完成，所以所有任务之间不需要做额外的同步操作。

### 完成度遍历

使用`onCompletion`来处理补全是不适用的或者不方便的，你可以使用方法firstComplete()和nextComplete()来创建自定义遍历。例如定义一个MapReducer，它只把右半部分任务分出去。

```java
class MapReducer<E> extends CountedCompleter<E> { // version 2
    final E[] array; final MyMapper<E> mapper;
    final MyReducer<E> reducer; final int lo, hi;
    MapReducer<E> forks, next; // record subtask forks in list
    E result;
    MapReducer(CountedCompleter<?> p, E[] array, MyMapper<E> mapper,
               MyReducer<E> reducer, int lo, int hi, MapReducer<E> next) {
        super(p);
        this.array = array; this.mapper = mapper;
        this.reducer = reducer; this.lo = lo; this.hi = hi;
        this.next = next;
    }
    public void compute() {
        int l = lo,  h = hi;
        while (h - l >= 2) {
            int mid = (l + h) >>> 1;
            addToPendingCount(1);
            (forks = new MapReducer(this, array, mapper, reducer, mid, h, forks)).fork();
            h = mid;
        }
        if (h > l)
            result = mapper.apply(array[l]);
        // process completions by reducing along and advancing subtask links
        for (CountedCompleter<?> c = firstComplete(); c != null; c = c.nextComplete()) {
            for (MapReducer t = (MapReducer)c, s = t.forks;  s != null; s = t.forks = s.next)
                t.result = reducer.apply(t.result, s.result);
        }
    }
    public E getRawResult() { return result; }

    public static <E> E mapReduce(E[] array, MyMapper<E> mapper, MyReducer<E> reducer) {
        return new MapReducer<E>(null, array, mapper, reducer,
                                 0, array.length, null).invoke();
    }
}
```

### 触发器

有些`CountedCompleter`自己不执行任何任务，而是在其他异步任务完成时触发一个特定的方法。

```java
class HeaderBuilder extends CountedCompleter<...> { ... }
class BodyBuilder extends CountedCompleter<...> { ... }
class PacketSender extends CountedCompleter<...> {
    PacketSender(...) { super(null, 1); ... } // trigger on second completion
    public void compute() { } // never called
    public void onCompletion(CountedCompleter<?> caller) { sendPacket(); }
}
// sample use:
PacketSender p = new PacketSender();
new HeaderBuilder(p, ...).fork();
new BodyBuilder(p, ...).fork();
```