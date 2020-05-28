# JavaSourceLearn

本项目是我的Java源码学习项目，我计划以源码注解+博客总结的方式学习Java中一些重要的类库，希望也能帮助到同样在学习Java的同学。

我的博客站点：https://blog.andycen.com

源码拷贝方式参考的是这个视频：https://www.bilibili.com/video/BV1V7411U78L

## 调试环境

系统：MacOS

jdk版本：1.8.0_201

## 项目结构

```
├──documents -- 类级别的JavaDoc译文、以及一些篇幅过长不宜放在源码注解中的内容
├──src
  ├── source -- 整个jdk目录的拷贝
  ├── test -- 一些测试类
```

## 导航

### Map

#### HashMap

<a href="https://github.com/cenry/JavaSourceLearn/blob/master/src/com/andycen/source/java/util/HashMap.java">源码解析</a>｜<a href="https://github.com/cenry/JavaSourceLearn/blob/master/documents/HashMap.md">文档翻译与附录</a>｜<a href="https://blog.andycen.com/2020/04/24/Java%E9%9B%86%E5%90%88%E6%A1%86%E6%9E%B6%E4%B9%8BHashMap/">博客文章</a> 

#### Hashtable

<a href="https://github.com/cenry/JavaSourceLearn/blob/master/src/com/andycen/source/java/util/Hashtable.java">源码解析</a>｜<a href="https://github.com/cenry/JavaSourceLearn/blob/master/documents/Hashtable.md">文档翻译</a>｜<a href="https://blog.andycen.com/2020/05/02/Java%E9%9B%86%E5%90%88%E6%A1%86%E6%9E%B6%E4%B9%8BHashtable/">博客文章</a>

#### LinkedHashMap

<a href="https://github.com/cenry/JavaSourceLearn/blob/master/src/com/andycen/source/java/util/LinkedHashMap.java">源码解析</a>｜<a href="https://github.com/cenry/JavaSourceLearn/blob/master/documents/LinkedHashMap.md">文档翻译</a>｜<a href="https://blog.andycen.com/2020/05/03/Java%E9%9B%86%E5%90%88%E6%A1%86%E6%9E%B6%E4%B9%8BLinkedHashMap/">博客文章</a>

#### ConcurrentHashMap

<a href="https://github.com/cenry/JavaSourceLearn/blob/master/src/com/andycen/source/java/util/concurrent/ConcurrentHashMap.java">源码解析</a>｜<a href="https://github.com/cenry/JavaSourceLearn/blob/master/documents/ConcurrentHashMap.md">文档翻译</a>｜<a href="https://blog.andycen.com/2020/05/11/Java%E9%9B%86%E5%90%88%E6%A1%86%E6%9E%B6%E4%B9%8BConcurrentHashMap/">博客文章</a>

#### TreeMap

<a href="https://github.com/cenry/JavaSourceLearn/blob/master/src/com/andycen/source/java/util/TreeMap.java">源码解析</a>｜<a href="https://blog.andycen.com/2020/05/28/Java%E9%9B%86%E5%90%88%E6%A1%86%E6%9E%B6%E4%B9%8BTreeMap/">博客文章</a>

### 多线程

#### Fork-Join线程池

<a href="https://blog.andycen.com/2020/05/20/Java%E5%A4%9A%E7%BA%BF%E7%A8%8B%E6%A1%86%E6%9E%B6%E4%B9%8BFork-Join%E7%BA%BF%E7%A8%8B%E6%B1%A0/">博客文章</a>