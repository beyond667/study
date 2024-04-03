### HashMap & ArrayMap & SparseArray

#### 对比

在Android移动设备中，android为了内存优化专门设计了ArrayMap和SparseArray，在数据量小的情况下（数量小于1000），用ArrayMap和SparseArray理论上比HashMap更省内存。
HashMap和ArrayMap都是实现的Map接口，SparseArray原理和ArrayMap大部分都一样，区别是key只能是int型，所以其没有实现Map接口，因为要实现Map接口，其key必须是object对象。我们详细看下这三者的区别：

| 不同点|HashMap|ArrayMap|SparseArray|
| --------- |-------------|---------------|--------------|
|出处  | Java |  Android4.4 |Android4.4（以下必须引support包）|
|数据结构|1.8之前是数组+单链表，1,8之后再加红黑树（链表长度大于8变树，树长度减少到6时变回单链表）|两个数组，一个数组存key的hash值（从小到大排序），一个存key和value|两个数组，一个存key（从小到大排序），一个存value|
|操作复杂度|理想状态下1，在数组上直接查到，单链表上是n,红黑树是logN|在key的列表二分法查找，logN|同ArrayMap|
|默认长度|16|0|10|
|扩容时机|大于size*0.75（加载因子默认0.75），并且数组长度大于等于64| 插入时数组已满|同ArrayMap|
|扩容机制|乘2|0>4>8>之后一次扩0.5倍即>12>18>27（类似ArrayList，不过其第一次是0>10）|同ArrayMap|
|缩容时机 | 不能缩容，假如数组长度32，就算把数据删完长度也不会变为16 | 数组长度大于8，删除时使用的空间小于1/3即缩容，缩小到原来的2/3 | 同ArrayMap|
|使用场景|数据量大或者需要高效查询，但是消耗更多内存|数据小于1000，且对内存占用要求较高|同ArrayMap，且key是int时|

#### HashMap

```java
public class HashMap<K,V> extends AbstractMap<K,V>
    implements Map<K,V>, Cloneable, Serializable {
    //1 默认加载因子0.75 默认初始化容量16
    static final float DEFAULT_LOAD_FACTOR = 0.75f;
    static final int DEFAULT_INITIAL_CAPACITY = 1 << 4; // aka 16
     
    /**
     * The table, initialized on first use, and resized as
     * necessary. When allocated, length is always a power of two.
     * (We also tolerate length zero in some operations to allow
     * bootstrapping mechanics that are currently not needed.)
     */
    // 注释写的很清楚，Node数组，第一次使用时才初始化，有需要时就扩容，每次扩2倍
    transient Node<K,V>[] table;

    /**
     * Holds cached entrySet(). Note that AbstractMap fields are used
     * for keySet() and values().
     */
    //set集合里存的单链表或者树
    transient Set<Map.Entry<K,V>> entrySet;
    
    public HashMap() {
        this.loadFactor = DEFAULT_LOAD_FACTOR; // all other fields defaulted
    }
    //2 手动传容量或者加载因子
    public HashMap(int initialCapacity) {
        this(initialCapacity, DEFAULT_LOAD_FACTOR);
    }
    public HashMap(int initialCapacity, float loadFactor) {
        if (initialCapacity > MAXIMUM_CAPACITY)
            initialCapacity = MAXIMUM_CAPACITY;
        this.loadFactor = loadFactor;
        this.threshold = tableSizeFor(initialCapacity);
    }
    static final int MAXIMUM_CAPACITY = 1 << 30;

}
```

+ 注释1默认的加载因子0.75，我们直接new HashMap()时并没有初始化数组，此时数组长度为0，只有在往map里存值时才会通过resize把数组初始化长度16
+ 注释2如果我们确定map的长度，可以在new HashMap时设置大小，这样就避免一次往map里塞很多值时进行扩容耗时操作。另注意，设置大小时尽量是2的n次方，比如2,4,8,16,32，尽量避免奇数或者2的奇数倍，比如5，6，因为这么设置会加剧hash冲突

我们具体看下put插入流程：

```java
public V put(K key, V value) {
    return putVal(hash(key), key, value, false, true);
}
static final int hash(Object key) {
    int h;
    //计算key的hashCode
    return (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16);
}

```

计算key的hashCode值也比较有意思，并不是直接return的key.hashCode()，而是把key.hashCode()得到的值与此值右移16位做了异或操作，主要为了增加低位的随机性

```java
final V putVal(int hash, K key, V value, boolean onlyIfAbsent,boolean evict) {
    Node<K,V>[] tab; Node<K,V> p; int n, i;
    //1 table为空，调用resize初始化长度为16
    if ((tab = table) == null || (n = tab.length) == 0)
        n = (tab = resize()).length;
    //2 hash值与长度-1做了并运行，相当于hash%(n-1),确定数组下标
    // 如果此处还没数据，直接把此数据放到数组的这个位置，否则说明有hash冲突，此时p有可能是链表，也可能是树
    if ((p = tab[i = (n - 1) & hash]) == null)
        tab[i] = newNode(hash, key, value, null);
    else {
        Node<K,V> e; K k;
        //3 如果put的key与根节点的key完全相等，直接赋值到根节点即可
        if (p.hash == hash &&
            ((k = p.key) == key || (key != null && key.equals(k))))
            e = p;
        //4 如果是树，通过putTreeVal放到合适的位置
        else if (p instanceof TreeNode)
            e = ((TreeNode<K,V>)p).putTreeVal(this, tab, hash, key, value);
        else {
            //5 如果是单链表，如果到队尾都没找到，那直接在队尾插入（java1.7是插入队头），能找到就直接break循环
            for (int binCount = 0; ; ++binCount) {
                if ((e = p.next) == null) {
                    p.next = newNode(hash, key, value, null);
                    if (binCount >= TREEIFY_THRESHOLD - 1) // -1 for 1st
                        treeifyBin(tab, hash);
                    break;
                }
                if (e.hash == hash &&
                    ((k = e.key) == key || (key != null && key.equals(k))))
                    break;
                p = e;
            }
        }
        //6 如果找到，直接替换值即可，不需要走后面的判断是否需要扩容，返回的是旧值
        if (e != null) { // existing mapping for key
            V oldValue = e.value;
            if (!onlyIfAbsent || oldValue == null)
                e.value = value;
            afterNodeAccess(e);
            return oldValue;
        }
    }
    //7 没找到，需判断是否需要扩容，threshold值即为数组长度*加载因子
    ++modCount;
    if (++size > threshold)
        resize();
    afterNodeInsertion(evict);
    return null;
}
```

+ 注释1 第一次往map里put时，会先resize初始化数组长度为16
+ 注释2 hash值与长度-1做了并运行，相当于hash%(n-1)，但是只有长度为2的n次方才相等。先确定数组下标，如果此处还没数据，直接把此数据放到数组的这个位置，否则说明有hash冲突
+ 注释3 4 5就是判断要把新put的节点放到哪个位置，可能是数组的根节点，也可能是树里或链表里
+ 注释6 7判断是否是新增的，如果是新增的，需判断是否走扩容逻辑，否则直接替换原值

删除的流程与插入相反

```java
public V remove(Object key) {
    Node<K,V> e;
    return (e = removeNode(hash(key), key, null, false, true)) == null ?
        null : e.value;
}
final Node<K,V> removeNode(int hash, Object key, Object value,
                           boolean matchValue, boolean movable) {
    Node<K,V>[] tab; Node<K,V> p; int n, index;
    //表不为空，并且取余后的节点也不为空才往下走，否则直接返回null
    if ((tab = table) != null && (n = tab.length) > 0 &&
        (p = tab[index = (n - 1) & hash]) != null) {
        Node<K,V> node = null, e; K k; V v;
        //同添加 一样的套路，先找到具体的节点，如果是在数组的根节点，直接返回
        if (p.hash == hash &&
            ((k = p.key) == key || (key != null && key.equals(k))))
            node = p;
        else if ((e = p.next) != null) {
            if (p instanceof TreeNode)
                //如果是树结构，从树里找
                node = ((TreeNode<K,V>)p).getTreeNode(hash, key);
            else {
                do {
                    //否则从单链表里找，直到查到最后
                    if (e.hash == hash &&
                        ((k = e.key) == key ||
                         (key != null && key.equals(k)))) {
                        node = e;
                        break;
                    }
                    p = e;
                } while ((e = e.next) != null);
            }
        }
        //能找到此元素就走里面移除逻辑
        if (node != null && (!matchValue || (v = node.value) == value ||
                             (value != null && value.equals(v)))) {
            if (node instanceof TreeNode)
                //1 通过removeTreeNode从树里移除元素，可能会把树变为链表
                ((TreeNode<K,V>)node).removeTreeNode(this, tab, movable);
            else if (node == p)
                //如果找到的是数组的根节点，直接把数组指向移除元素的下一个元素，相当于把该元素删除
                tab[index] = node.next;
            else
                //如果是单链表里面的，前一个元素的next指向找到元素的next元素，相当于把查找到元素删除
                p.next = node.next;
            ++modCount;
            --size;
            afterNodeRemoval(node);
            return node;
        }
    }
    return null;
}
```

删除和插入逻辑很像，不赘述。需关注删除时注释1处通过removeTreeNode从树里移除元素，可能会把树变为链表

```java
final void removeTreeNode(HashMap<K,V> map, Node<K,V>[] tab,boolean movable) {
    int n;
    if (tab == null || (n = tab.length) == 0)
        return;
    int index = (n - 1) & hash;
    TreeNode<K,V> first = (TreeNode<K,V>)tab[index], root = first, rl;
    TreeNode<K,V> succ = (TreeNode<K,V>)next, pred = prev;
    //...
    //1 树变链表的条件
    if (root == null || root.right == null ||
        (rl = root.left) == null || rl.left == null) {
        tab[index] = first.untreeify(map);  // too small
        return;
    }
    //...
}
```

可以看到删除元素时，如果树的根，根的右子树，根的左子树，跟的左子树的左子树有一个为空，都会把树变为链表。所以理论上如果这4个位置都不为空，即树长度只有4也不会变为链表。还有个场景也会把树变为链表，即在数组扩容时，树的一部分数据可能要移动到新扩容的数组下，此树的数据少了当然可能会退回链表。

继续看put时调用的resize扩容

```java
static final int DEFAULT_INITIAL_CAPACITY = 1 << 4; // aka 16
final Node<K,V>[] resize() {
    Node<K,V>[] oldTab = table;
    int oldCap = (oldTab == null) ? 0 : oldTab.length;
    int oldThr = threshold;
    int newCap, newThr = 0;
    if (oldCap > 0) {
        if (oldCap >= MAXIMUM_CAPACITY) {
            threshold = Integer.MAX_VALUE;
            return oldTab;
        }
        else if ((newCap = oldCap << 1) < MAXIMUM_CAPACITY &&
                 oldCap >= DEFAULT_INITIAL_CAPACITY)
            //容量和阈值都扩容2倍
            newThr = oldThr << 1; // double threshold
    }
    else if (oldThr > 0) // initial capacity was placed in threshold
        newCap = oldThr;
    else {               // zero initial threshold signifies using defaults
        //1 第一次put数据时初始化容量为16，默认扩容的阈值为12
        newCap = DEFAULT_INITIAL_CAPACITY;
        newThr = (int)(DEFAULT_LOAD_FACTOR * DEFAULT_INITIAL_CAPACITY);
    }
    if (newThr == 0) {
        float ft = (float)newCap * loadFactor;
        newThr = (newCap < MAXIMUM_CAPACITY && ft < (float)MAXIMUM_CAPACITY ?
                  (int)ft : Integer.MAX_VALUE);
    }
    threshold = newThr;
    //新建的Node数组是原数组的2倍
    Node<K,V>[] newTab = (Node<K,V>[])new Node[newCap];
    table = newTab;
    if (oldTab != null) {
        //遍历原数组
        for (int j = 0; j < oldCap; ++j) {
            Node<K,V> e;
            //索引处不为空才往下走
            if ((e = oldTab[j]) != null) {
                oldTab[j] = null;
                //如果根进点的next为空，说明该位置只有一个数组，直接放到新数组即可
                if (e.next == null)
                    newTab[e.hash & (newCap - 1)] = e;
                else if (e instanceof TreeNode)
                    //2 如果当前是树，调用split去分割树，此时可能树变链表
                    ((TreeNode<K,V>)e).split(this, newTab, j, oldCap);
                else { // preserve order
                    //否则是链表，采用尾插法
                    Node<K,V> loHead = null, loTail = null;
                    Node<K,V> hiHead = null, hiTail = null;
                    Node<K,V> next;
                    do {
                        next = e.next;
                        if ((e.hash & oldCap) == 0) {
                            if (loTail == null)
                                loHead = e;
                            else
                                loTail.next = e;
                            loTail = e;
                        }
                        else {
                            if (hiTail == null)
                                hiHead = e;
                            else
                                hiTail.next = e;
                            hiTail = e;
                        }
                    } while ((e = next) != null);
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
```

+ 注释1处是首次put时会初始化长度为16，扩容的阈值为12
+ 注释2处扩容的当前节点是树的话，调用split去分割树

```java
static final int UNTREEIFY_THRESHOLD = 6;
final void split(HashMap<K,V> map, Node<K,V>[] tab, int index, int bit) {
    //红黑树在node数组上的元素
    TreeNode<K,V> b = this;
    //loHead 低位树头结点， loTail 低位树尾结点
    TreeNode<K,V> loHead = null, loTail = null;
    //hiHead 高位树头结点， hiTail 高位树尾结点
    TreeNode<K,V> hiHead = null, hiTail = null;
    int lc = 0, hc = 0;
    //红黑树为链表转换而成，hashmap链表节点（node类型）在转换成红黑树（treeNode类型）时
    //保留了原有node节点的变量(next等等)，数据，用于进行迭代器遍历，及退化为链表

    //虽然是红黑树，不过保留了next，可以按照链表方式进行遍历
    for (TreeNode<K,V> e = b, next; e != null; e = next) {
        next = (TreeNode<K,V>)e.next;
        e.next = null;
        //如果当前节点hash与运算扩容前map容量为0，代表扩容后索引位置不变
        //扩容后索引位置不变的节点放在低位树中
        if ((e.hash & bit) == 0) {
            //尾结点为空，此时还未放入输入，设置头结点为e
            if ((e.prev = loTail) == null)
                loHead = e;
            //尾结点不为空，此时已有数据，将尾结点next指向当前数据(尾插法)
            else
                loTail.next = e;
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
    //lc,hc从0开始增加，lo树中有6个元素，则lc=6
    //hi树有6个元素，则hc=6
    if (loHead != null) {
        //1 lc<=6，将lo树退化为链表，也就是lc树中有6个元素，就会退化为链表
        if (lc <= UNTREEIFY_THRESHOLD)
            tab[index] = loHead.untreeify(map);

        /**lc>6 将lc转化为真正的红黑树结构
        *前面lc只是next赋值了，还只是链表结构
        *红黑树需要对parent,left,right等属性赋值
        */
            else {
                tab[index] = loHead;
                if (hiHead != null)  
                    loHead.treeify(tab);
            }
    }
    //hi树与lo同理
    if (hiHead != null) {
        if (hc <= UNTREEIFY_THRESHOLD)
            tab[index + bit] = hiHead.untreeify(map);
        else {
            tab[index + bit] = hiHead;
            if (loHead != null)
                hiHead.treeify(tab);
        }
    }
}
```

这里可以看到在扩容时如果链表长度小于等于6就会进行树变链表。

#### ArrayMap

