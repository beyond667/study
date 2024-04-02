### HashMap & ArrayMap & SparseArray

#### 对比

在Android移动设备中，android为了内存优化专门设计了ArrayMap和SparseArray，在数据量小的情况下（数量小于1000），用ArrayMap和SparseArray理论上比HashMap更省内存。
HashMap和ArrayMap都是实现的Map接口，SparseArray原理和ArrayMap大部分都一样，区别是key只能是int型，所以其没有实现Map接口，因为要实现Map接口，其key必须是object对象。我们详细看下这三者的区别：

| 不同点|HashMap|ArrayMap|SparseArray
| --------- |-------------|---------------|--------------
出处  | Java |  Android4.4 |Android4.4（以下必须引support包）
数据结构|1.7之前是数组+单链表，1,7之后再加红黑树|两个数组，一个数组存key的hash值（从小到大排序），一个存key和value|两个数组，一个存key（从小到大排序），一个存value
操作复杂度|理想状态下1，在数组上直接查到，单链表上是n,红黑树是logN|在key的列表二分法查找，logN|同ArrayMap
默认长度|16|0|10
扩容时机|大于size*0.75（加载因子默认0.75）| 
扩容机制|乘2|0>4>8>之后一次扩0.5倍即>12>18>27（类似ArrayList，不过其第一次是0>10）|同ArrayMap




