/* SnapTree - (c) 2009 Stanford University - PPL */

// SnapTreeMap

package edu.stanford.ppl.concurrent;

import java.util.*;
import java.util.concurrent.ConcurrentNavigableMap;

// TODO: serialization
// TODO: submap
// TODO: optimized buildFromSorted

public class SnapTreeMap<K,V> extends AbstractMap<K,V> implements ConcurrentNavigableMap<K,V>, Cloneable {

    /** This is a special value that indicates the presence of a null value,
     *  to differentiate from the absence of a value.
     */
    static final Object SpecialNull = new Object();

    /** This is a special value that indicates that an optimistic read
     *  failed.
     */ 
    static final Object SpecialRetry = new Object();


    /** The number of spins before yielding. */
    static final int SpinCount = Integer.parseInt(System.getProperty("snaptree.spin", "100"));

    /** The number of yields before blocking. */
    static final int YieldCount = Integer.parseInt(System.getProperty("snaptree.yield", "0"));

    
    // we encode directions as characters
    static final char Left = 'L';
    static final char Right = 'R';


    /** An <tt>OVL</tt> is a version number and lock used for optimistic
     *  concurrent control of some program invariant.  If  {@link #isShrinking}
     *  then the protected invariant is changing.  If two reads of an OVL are
     *  performed that both see the same non-changing value, the reader may
     *  conclude that no changes to the protected invariant occurred between
     *  the two reads.  The special value UnlinkedOVL is not changing, and is
     *  guaranteed to not result from a normal sequence of beginChange and
     *  endChange operations.
     *  <p>
     *  For convenience <tt>endChange(ovl) == endChange(beginChange(ovl))</tt>.
     */
    static long beginChange(long ovl) { return ovl | 1; }
    static long endChange(long ovl) { return (ovl | 3) + 1; }
    static long UnlinkedOVL = 2;

    static boolean isShrinking(long ovl) { return (ovl & 1) != 0; }
    static boolean isUnlinked(long ovl) { return (ovl & 2) != 0; }
    static boolean isShrinkingOrUnlinked(long ovl) { return (ovl & 3) != 0L; }


    private static class Node<K,V> implements Map.Entry<K,V> {
        final K key;
        volatile int height;

        /** null means this node is conceptually not present in the map.
         *  SpecialNull means the value is null.
         */
        volatile Object vOpt;
        volatile Node<K,V> parent;
        volatile long shrinkOVL;
        volatile Node<K,V> left;
        volatile Node<K,V> right;

        Node(final K key,
              final int height,
              final Object vOpt,
              final Node<K,V> parent,
              final long shrinkOVL,
              final Node<K,V> left,
              final Node<K,V> right)
        {
            this.key = key;
            this.height = height;
            this.vOpt = vOpt;
            this.parent = parent;
            this.shrinkOVL = shrinkOVL;
            this.left = left;
            this.right = right;
        }

        @Override
        public K getKey() { return key; }

        @Override
        @SuppressWarnings("unchecked")
        public V getValue() {
            final Object tmp = vOpt;
            return tmp == SpecialNull ? null : (V)tmp;
        }

        @Override
        public V setValue(final V v) {
            throw new UnsupportedOperationException();
        }

        Node<K,V> child(char dir) { return dir == Left ? left : right; }
        Node<K,V> childSibling(char dir) { return dir == Left ? right : left; }

        void setChild(char dir, Node<K,V> node) {
            if (dir == Left) {
                left = node;
            } else {
                right = node;
            }
        }

        //////// copy-on-write stuff

        private static <K,V> boolean isShared(final Node<K,V> node) {
            return node != null && node.parent == null; 
        }

        static <K,V> Node<K,V> markShared(final Node<K,V> node) {
            if (node != null) {
                node.parent = null;
            }
            return node;
        }

        private Node<K,V> lazyCopy(Node<K,V> newParent) {
            assert (isShared(this));
            assert (!isShrinkingOrUnlinked(shrinkOVL));

            return new Node<K,V>(key, height, vOpt, newParent, 0L, markShared(left), markShared(right));
        }

        Node<K,V> unsharedLeft() {
            final Node<K,V> cl = left;
            if (!isShared(cl)) {
                return cl;
            } else {
                lazyCopyChildren();
                return left;
            }
        }

        Node<K,V> unsharedRight() {
            final Node<K,V> cr = right;
            if (!isShared(cr)) {
                return cr;
            } else {
                lazyCopyChildren();
                return right;
            }
        }

        Node<K,V> unsharedChild(final char dir) {
            return dir == Left ? unsharedLeft() : unsharedRight();
        }

        private synchronized void lazyCopyChildren() {
            final Node<K,V> cl = left;
            if (isShared(cl)) {
                left = cl.lazyCopy(this);
            }
            final Node<K,V> cr = right;
            if (isShared(cr)) {
                right = cr.lazyCopy(this);
            }
        }

        //////// per-node blocking

        private void waitUntilShrinkCompleted(final long ovl) {
            if (!isShrinking(ovl)) {
                return;
            }

            for (int tries = 0; tries < SpinCount; ++tries) {
                if (shrinkOVL != ovl) {
                    return;
                }
            }

            for (int tries = 0; tries < YieldCount; ++tries) {
                Thread.yield();
                if (shrinkOVL != ovl) {
                    return;
                }
            }

            // spin and yield failed, use the nuclear option
            synchronized (this) {
                // we can't have gotten the lock unless the shrink was over
            }
            assert(shrinkOVL != ovl);
        }

        int validatedHeight() {
            final int hL = left == null ? 0 : left.validatedHeight();
            final int hR = right == null ? 0 : right.validatedHeight();
            assert(Math.abs(hL - hR) <= 1);
            final int h = 1 + Math.max(hL, hR);
            assert(h == height);
            return height;
        }

        //////// Map.Entry stuff

        public boolean equals(final Object o) {
            if (!(o instanceof Map.Entry)) {
                return false;
            }
            final Map.Entry rhs = (Map.Entry)o;
            return eq(key, rhs.getKey()) && eq(getValue(), rhs.getValue());
        }

        private static boolean eq(final Object o1, final Object o2) {
            return o1 == null ? o2 == null : o1.equals(o2);
        }

        public int hashCode() {
            return (key   == null ? 0 : key.hashCode()) ^
                   (getValue() == null ? 0 : getValue().hashCode());
        }

        public String toString() {
            return key + "=" + getValue();
        }

    }

    private static class RootHolder<K,V> extends Node<K,V> {
        RootHolder() {
            super(null, 1, null, null, 0L, null, null);
        }

        RootHolder(final RootHolder<K,V> snapshot) {
            super(null, 1 + snapshot.height, null, null, 0L, null, snapshot.right);
        }
    }

    private static class COWMgr<K,V> extends CopyOnWriteManager<RootHolder<K,V>> {
        COWMgr() {
            super(new RootHolder<K,V>(), 0);
        }

        COWMgr(final RootHolder<K,V> initialValue, final int initialSize) {
            super(initialValue, initialSize);
        }

        protected RootHolder<K,V> freezeAndClone(final RootHolder<K,V> value) {
            Node.markShared(value.right);
            return new RootHolder(value); 
        }

        protected RootHolder<K, V> cloneFrozen(final RootHolder<K, V> frozenValue) {
            return new RootHolder(frozenValue);
        }
    }

    //////// node access functions

    private static int height(final Node<?,?> node) {
        return node == null ? 0 : node.height;
    }

    @SuppressWarnings("unchecked")
    private V decodeNull(final Object vOpt) {
        assert (vOpt != SpecialRetry);
        return vOpt == SpecialNull ? null : (V)vOpt;
    }

    private static Object encodeNull(final Object v) {
        return v == null ? SpecialNull : v;
    }

    //////////////// state

    private Comparator<? super K> comparator;
    private volatile COWMgr<K,V> holderRef;

    //////////////// public interface

    public SnapTreeMap() {
        this.comparator = null;
        this.holderRef = new COWMgr<K,V>();
    }

    public SnapTreeMap(final Comparator<? super K> comparator) {
        this.comparator = comparator;
        this.holderRef = new COWMgr<K,V>();
    }

    public SnapTreeMap(final Map<? extends K, ? extends V> source) {
        this.comparator = null;
        this.holderRef = new COWMgr<K,V>();
        putAll(source);
    }

    public SnapTreeMap(final SortedMap<K,? extends V> source) {
        this.comparator = source.comparator();
        if (source instanceof SnapTreeMap) {
            final SnapTreeMap<K,V> s = (SnapTreeMap<K,V>) source;
            this.holderRef = (COWMgr<K,V>) s.holderRef.clone();
        }
        else {
            this.holderRef = new COWMgr<K,V>();
            // TODO: implement buildFromSorted
            putAll(source);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public SnapTreeMap<K,V> clone() {
        final SnapTreeMap<K,V> copy;
        try {
            copy = (SnapTreeMap<K,V>) super.clone();
        } catch (final CloneNotSupportedException xx) {
            throw new InternalError();
        }
        assert(copy.comparator == comparator);
        copy.holderRef = (COWMgr<K,V>) holderRef.clone();
        return copy;
    }

    @Override
    public int size() {
        return holderRef.size();
    }

    @Override
    public boolean isEmpty() {
        // removed-but-not-unlinked nodes cannot be leaves, so if the tree is
        // truly empty then the root holder has no right child
        return holderRef.read().right == null;
    }

    @Override
    public void clear() {
        holderRef = new COWMgr<K,V>();
    }

    public Comparator<? super K> comparator() {
        return comparator;
    }

    //////// concurrent search

    @Override
    public boolean containsKey(final Object key) {
        return getImpl(key) != null;
    }

    @Override
    public V get(final Object key) {
        return decodeNull(getImpl(key));
    }

    @SuppressWarnings("unchecked")
    private Comparable<? super K> comparable(final Object key) {
        if (key == null) {
            throw new NullPointerException();
        }
        if (comparator == null) {
            return (Comparable<? super K>)key;
        }
        return new Comparable<K>() {
            final Comparator<? super K> _cmp = comparator;

            @SuppressWarnings("unchecked")
            public int compareTo(final K rhs) { return _cmp.compare((K)key, rhs); }
        };
    }

    /** Returns either a value or SpecialNull, if present, or null, if absent. */
    private Object getImpl(final Object key) {
        final Comparable<? super K> k = comparable(key);
        
        while (true) {
            final Node<K,V> right = holderRef.read().right;
            if (right == null) {
                return null;
            } else {
                final int rightCmp = k.compareTo(right.key);
                if (rightCmp == 0) {
                    // who cares how we got here
                    return right.vOpt;
                }

                final long ovl = right.shrinkOVL;
                if (isShrinkingOrUnlinked(ovl)) {
                    right.waitUntilShrinkCompleted(ovl);
                    // RETRY
                } else if (right == holderRef.read().right) {
                    // the reread of .right is the one protected by our read of ovl
                    final Object vo = attemptGet(k, right, (rightCmp < 0 ? Left : Right), ovl);
                    if (vo != SpecialRetry) {
                        return vo;
                    }
                    // else RETRY
                }
            }
        }
    }

    private Object attemptGet(final Comparable<? super K> k,
                              final Node<K,V> node,
                              final char dirToC,
                              final long nodeOVL) {
        while (true) {
            final Node<K,V> child = node.child(dirToC);

            if (child == null) {
                if (node.shrinkOVL != nodeOVL) {
                    return SpecialRetry;
                }

                // Note is not present.  Read of node.child occurred while
                // parent.child was valid, so we were not affected by any
                // shrinks.
                return null;
            } else {
                final int childCmp = k.compareTo(child.key);
                if (childCmp == 0) {
                    // how we got here is irrelevant
                    return child.vOpt;
                }

                // child is non-null
                final long childOVL = child.shrinkOVL;
                if (isShrinkingOrUnlinked(childOVL)) {
                    child.waitUntilShrinkCompleted(childOVL);

                    if (node.shrinkOVL != nodeOVL) {
                        return SpecialRetry;
                    }
                    // else RETRY
                } else if (child != node.child(dirToC)) {
                    // this .child is the one that is protected by childOVL
                    if (node.shrinkOVL != nodeOVL) {
                        return SpecialRetry;
                    }
                    // else RETRY
                } else {
                    if (node.shrinkOVL != nodeOVL) {
                        return SpecialRetry;
                    }

                    // At this point we know that the traversal our parent took
                    // to get to node is still valid.  The recursive
                    // implementation will validate the traversal from node to
                    // child, so just prior to the nodeOVL validation both
                    // traversals were definitely okay.  This means that we are
                    // no longer vulnerable to node shrinks, and we don't need
                    // to validate nodeOVL any more.
                    final Object vo = attemptGet(k, child, (childCmp < 0 ? Left : Right), childOVL);
                    if (vo != SpecialRetry) {
                        return vo;
                    }
                    // else RETRY
                }
            }
        }
    }

    public K firstKey() {
        return (K) extremeOrThrow(true, Left);
    }

    public Map.Entry<K,V> firstEntry() {
        return (SimpleImmutableEntry<K,V>) extremeOrThrow(false, Left);
    }

    public K lastKey() {
        return (K) extremeOrThrow(true, Right);
    }

    public Map.Entry<K,V> lastEntry() {
        return (SimpleImmutableEntry<K,V>) extremeOrThrow(false, Right);
    }

    private Object extremeOrThrow(final boolean returnKey, final char dir) {
        final Object z = extreme(returnKey, dir);
        if (z == null) {
            throw new NoSuchElementException();
        }
        return z;
    }

    /** Returns a key if returnKey is true, a SimpleImmutableEntry otherwise.
     *  Returns null if none exists.
     */
    private Object extreme(final boolean returnKey, final char dir) {
        while (true) {
            final Node<K,V> right = holderRef.read().right;
            if (right == null) {
                return null;
            } else {
                final long ovl = right.shrinkOVL;
                if (isShrinkingOrUnlinked(ovl)) {
                    right.waitUntilShrinkCompleted(ovl);
                    // RETRY
                } else if (right == holderRef.read().right) {
                    // the reread of .right is the one protected by our read of ovl
                    final Object vo = attemptExtreme(returnKey, dir, right, ovl);
                    if (vo != SpecialRetry) {
                        return vo;
                    }
                    // else RETRY
                }
            }
        }
    }

    private Object attemptExtreme(final boolean returnKey,
                                  final char dir,
                                  final Node<K,V> node,
                                  final long nodeOVL) {
        while (true) {
            final Node<K,V> child = node.child(dir);

            if (child == null) {
                // read of the value must be protected by the OVL, because we
                // must linearize against another thread that inserts a new min
                // key and then changes this key's value
                final Object vo = node.vOpt;

                if (node.shrinkOVL != nodeOVL) {
                    return SpecialRetry;
                }
                
                assert(vo != null);

                return returnKey ? node.key : new SimpleImmutableEntry<K,V>(node.key, decodeNull(vo));
            } else {
                // child is non-null
                final long childOVL = child.shrinkOVL;
                if (isShrinkingOrUnlinked(childOVL)) {
                    child.waitUntilShrinkCompleted(childOVL);

                    if (node.shrinkOVL != nodeOVL) {
                        return SpecialRetry;
                    }
                    // else RETRY
                } else if (child != node.child(dir)) {
                    // this .child is the one that is protected by childOVL
                    if (node.shrinkOVL != nodeOVL) {
                        return SpecialRetry;
                    }
                    // else RETRY
                } else {
                    if (node.shrinkOVL != nodeOVL) {
                        return SpecialRetry;
                    }

                    final Object vo = attemptExtreme(returnKey, dir, child, childOVL);
                    if (vo != SpecialRetry) {
                        return vo;
                    }
                    // else RETRY
                }
            }
        }
    }

    //////////////// quiesced search

    public K lowerKey(final K key) {
        return (K) boundedExtreme(null, false, comparable(key), false, true, Right);
    }
    public K floorKey(final K key) {
        return (K) boundedExtreme(null, false, comparable(key), true, true, Right);
    }
    public K ceilingKey(final K key) {
        return (K) boundedExtreme(comparable(key), true, null, false, true, Left);
    }
    public K higherKey(final K key) {
        return (K) boundedExtreme(comparable(key), false, null, false, true, Left); 
    }

    public Entry<K,V> lowerEntry(final K key) {
        return (Entry<K,V>) boundedExtreme(null, false, comparable(key), false, false, Right);
    }
    public Entry<K,V> floorEntry(final K key) {
        return (Entry<K,V>) boundedExtreme(null, false, comparable(key), true, false, Right);
    }
    public Entry<K,V> ceilingEntry(final K key) {
        return (Entry<K,V>) boundedExtreme(comparable(key), true, null, false, false, Left);
    }
    public Entry<K,V> higherEntry(final K key) {
        return (Entry<K,V>) boundedExtreme(comparable(key), false, null, false, false, Left);
    }

    /** Returns null if none exists. */
    private Object boundedExtremeOrThrow(final Comparable<? super K> minCmp,
                                         final boolean minIncl,
                                         final Comparable<? super K> maxCmp,
                                         final boolean maxIncl,
                                         final boolean returnKey,
                                         final char dir) {
        final Object z = boundedExtreme(minCmp, minIncl, maxCmp, maxIncl, returnKey, dir);
        if (z == null) {
            throw new NoSuchElementException();
        }
        return z;
    }

    /** Returns null if none exists. */
    private Object boundedExtreme(final Comparable<? super K> minCmp,
                                  final boolean minIncl,
                                  final Comparable<? super K> maxCmp,
                                  final boolean maxIncl,
                                  final boolean returnKey,
                                  final char dir) {
        K resultKey;
        Object result;

        if ((dir == Left && minCmp == null) || (dir == Right && maxCmp == null)) {
            // no bound in the extreme direction, so use the concurrent search
            result = extreme(returnKey, dir);
            if (result == null) {
                return null;
            }
            resultKey = returnKey ? (K) result : ((SimpleImmutableEntry<K,V>) result).getKey();
        }
        else {
            RootHolder holder = holderRef.availableFrozen();
            final Epoch.Ticket ticket;
            if (holder == null) {
                ticket = holderRef.beginQuiescent();
                holder = holderRef.read();
            }
            else {
                ticket = null;
            }
            try {
                final Node<K,V> node = (dir == Left)
                        ? boundedMin(holder.right, minCmp, minIncl)
                        : boundedMax(holder.right, maxCmp, maxIncl);
                if (node == null) {
                    return null;
                }
                resultKey = node.key;
                if (returnKey) {
                    result = node.key;
                }
                else if (ticket == null) {
                    // node of a frozen tree is okay, copy otherwise
                    result = node;
                }
                else {
                    // we must copy the node
                    result = new SimpleImmutableEntry<K,V>(node.key, node.getValue());
                }
            }
            finally {
                if (ticket != null) {
                    ticket.leave(0);
                }
            }
        }

        if (dir == Left && maxCmp != null) {
            final int c = maxCmp.compareTo(resultKey);
            if (c > 0 || (c == 0 && !maxIncl)) {
                return null;
            }
        }
        if (dir == Right && minCmp != null) {
            final int c = minCmp.compareTo(resultKey);
            if (c < 0 || (c == 0 && !minIncl)) {
                return null;
            }
        }

        return result;
    }

    private Node<K,V> boundedMin(Node<K,V> node,
                                 final Comparable<? super K> minCmp,
                                 final boolean minIncl) {
        while (node != null) {
            final int c = minCmp.compareTo(node.key);
            if (c < 0) {
                // there may be a matching node on the left branch
                final Node<K,V> z = boundedMin(node.left, minCmp, minIncl);
                if (z != null) {
                    return z;
                }
            }

            if (c < 0 || (c == 0 && minIncl)) {
                // this node is a candidate, is it actually present?
                if (node.vOpt != null) {
                    return node;
                }
            }

            // the matching node is on the right branch if it is present
            node = node.right;
        }
        return null;
    }

    private Node<K,V> boundedMax(Node<K,V> node,
                                 final Comparable<? super K> maxCmp,
                                 final boolean maxIncl) {
        while (node != null) {
            final int c = maxCmp.compareTo(node.key);
            if (c > 0) {
                // there may be a matching node on the right branch
                final Node<K,V> z = boundedMax(node.right, maxCmp, maxIncl);
                if (z != null) {
                    return z;
                }
            }

            if (c > 0 || (c == 0 && maxIncl)) {
                // this node is a candidate, is it actually present?
                if (node.vOpt != null) {
                    return node;
                }
            }

            // the matching node is on the left branch if it is present
            node = node.left;
        }
        return null;
    }

    //////////////// update

    private static final int UpdateAlways = 0;
    private static final int UpdateIfAbsent = 1;
    private static final int UpdateIfPresent = 2;
    private static final int UpdateIfEq = 3;

    private static boolean shouldUpdate(final int func, final Object prev, final Object expected) {
        switch (func) {
            case UpdateAlways: return true;
            case UpdateIfAbsent: return prev == null;
            case UpdateIfPresent: return prev != null;
            default: return prev == expected;
        }
    }

    @Override
    public V put(final K key, final V value) {
        return decodeNull(update(key, UpdateAlways, null, encodeNull(value)));
    }

    @Override
    public V putIfAbsent(final K key, final V value) {
        return decodeNull(update(key, UpdateIfAbsent, null, encodeNull(value)));
    }

    @Override
    public V replace(final K key, final V value) {
        return decodeNull(update(key, UpdateIfPresent, null, encodeNull(value)));
    }

    @Override
    public boolean replace(final K key, final V oldValue, final V newValue) {
        return update(key, UpdateIfEq, encodeNull(oldValue), encodeNull(newValue)) == encodeNull(oldValue);
    }

    @Override
    public V remove(final Object key) {
        return decodeNull(update(key, UpdateAlways, null, null));
    }

    @Override
    public boolean remove(final Object key, final Object value) {
        return update(key, UpdateIfEq, encodeNull(value), null) == encodeNull(value);
    }

    // manages the epoch
    private Object update(final Object key,
                          final int func,
                          final Object expected,
                          final Object newValue) {
        final Comparable<? super K> k = comparable(key);
        int sizeDelta = 0;
        final Epoch.Ticket ticket = holderRef.beginMutation();
        try {
            final Object prev = updateUnderRoot(key, k, func, expected, newValue, holderRef.mutable());
            if (shouldUpdate(func, prev, expected)) {
                sizeDelta = (prev != null ? -1 : 0) + (newValue != null ? 1 : 0);
            }
            return prev;
        } finally {
            ticket.leave(sizeDelta);
        }
    }

    // manages updates to the root holder
    @SuppressWarnings("unchecked")
    private Object updateUnderRoot(final Object key,
                                   final Comparable<? super K> k,
                                   final int func,
                                   final Object expected,
                                   final Object newValue,
                                   final RootHolder<K,V> holder) {

        while (true) {
            final Node<K,V> right = holder.unsharedRight();
            if (right == null) {
                // key is not present
                if (!shouldUpdate(func, null, expected) ||
                        newValue == null ||
                        attemptInsertIntoEmpty((K)key, newValue, holder)) {
                    // nothing needs to be done, or we were successful, prev value is Absent
                    return null;
                }
                // else RETRY
            } else {
                final long ovl = right.shrinkOVL;
                if (isShrinkingOrUnlinked(ovl)) {
                    right.waitUntilShrinkCompleted(ovl);
                    // RETRY
                } else if (right == holder.right) {
                    // this is the protected .right
                    final Object vo = attemptUpdate(key, k, func, expected, newValue, holder, right, ovl);
                    if (vo != SpecialRetry) {
                        return vo;
                    }
                    // else RETRY
                }
            }
        }
    }

    private boolean attemptInsertIntoEmpty(final K key,
                                           final Object vOpt,
                                           final RootHolder<K, V> holder) {
        synchronized (holder) {
            if (holder.right == null) {
                holder.right = new Node<K,V>(key, 1, vOpt, holder, 0L, null, null);
                holder.height = 2;
                return true;
            } else {
                return false;
            }
        }
    }

    /** If successful returns the non-null previous value, SpecialNull for a
     *  null previous value, or null if not previously in the map.
     *  The caller should retry if this method returns SpecialRetry.
     */
    @SuppressWarnings("unchecked")
    private Object attemptUpdate(final Object key,
                                 final Comparable<? super K> k,
                                 final int func,
                                 final Object expected,
                                 final Object newValue,
                                 final Node<K,V> parent,
                                 final Node<K,V> node,
                                 final long nodeOVL) {
        // As the search progresses there is an implicit min and max assumed for the
        // branch of the tree rooted at node. A left rotation of a node x results in
        // the range of keys in the right branch of x being reduced, so if we are at a
        // node and we wish to traverse to one of the branches we must make sure that
        // the node has not undergone a rotation since arriving from the parent.
        //
        // A rotation of node can't screw us up once we have traversed to node's
        // child, so we don't need to build a huge transaction, just a chain of
        // smaller read-only transactions.

        assert (nodeOVL != UnlinkedOVL);

        final int cmp = k.compareTo(node.key);
        if (cmp == 0) {
            return attemptNodeUpdate(func, expected, newValue, parent, node);
        }

        final char dirToC = cmp < 0 ? Left : Right;

        while (true) {
            final Node<K,V> child = node.unsharedChild(dirToC);

            if (node.shrinkOVL != nodeOVL) {
                return SpecialRetry;
            }

            if (child == null) {
                // key is not present
                if (newValue == null) {
                    // Removal is requested.  Read of node.child occurred
                    // while parent.child was valid, so we were not affected
                    // by any shrinks.
                    return null;
                } else {
                    // Update will be an insert.
                    final boolean success;
                    final Node<K,V> damaged;
                    synchronized (node) {
                        // Validate that we haven't been affected by past
                        // rotations.  We've got the lock on node, so no future
                        // rotations can mess with us.
                        if (node.shrinkOVL != nodeOVL) {
                            return SpecialRetry;
                        }

                        if (node.child(dirToC) != null) {
                            // Lost a race with a concurrent insert.  No need
                            // to back up to the parent, but we must RETRY in
                            // the outer loop of this method.
                            success = false;
                            damaged = null;
                        } else {
                            // We're valid.  Does the user still want to
                            // perform the operation?
                            if (!shouldUpdate(func, null, expected)) {
                                return null;
                            }

                            // Create a new leaf
                            node.setChild(dirToC, new Node<K,V>((K)key, 1, newValue, node, 0L, null, null));
                            success = true;

                            // attempt to fix node.height while we've still got
                            // the lock
                            damaged = fixHeight_nl(node);
                        }
                    }
                    if (success) {
                        fixHeightAndRebalance(damaged);
                        return null;
                    }
                    // else RETRY
                }
            } else {
                // non-null child
                final long childOVL = child.shrinkOVL;
                if (isShrinkingOrUnlinked(childOVL)) {
                    child.waitUntilShrinkCompleted(childOVL);
                    // RETRY
                } else if (child != node.child(dirToC)) {
                    // this second read is important, because it is protected
                    // by childOVL
                    // RETRY
                } else {
                    // validate the read that our caller took to get to node
                    if (node.shrinkOVL != nodeOVL) {
                        return SpecialRetry;
                    }

                    // At this point we know that the traversal our parent took
                    // to get to node is still valid.  The recursive
                    // implementation will validate the traversal from node to
                    // child, so just prior to the nodeOVL validation both
                    // traversals were definitely okay.  This means that we are
                    // no longer vulnerable to node shrinks, and we don't need
                    // to validate nodeOVL any more.
                    final Object vo = attemptUpdate(key, k, func, expected, newValue, node, child, childOVL);
                    if (vo != SpecialRetry) {
                        return vo;
                    }
                    // else RETRY
                }
            }
        }
    }

    /** parent will only be used for unlink, update can proceed even if parent
     *  is stale.
     */
    private Object attemptNodeUpdate(final int func,
                                     final Object expected,
                                     final Object newValue,
                                     final Node<K,V> parent,
                                     final Node<K,V> node) {
        if (newValue == null) {
            // removal
            if (node.vOpt == null) {
                // This node is already removed, nothing to do.
                return null;
            }
        }

        if (newValue == null && (node.left == null || node.right == null)) {
            // potential unlink, get ready by locking the parent
            final Object prev;
            final Node<K,V> damaged;
            synchronized (parent) {
                if (isUnlinked(parent.shrinkOVL) || node.parent != parent) {
                    return SpecialRetry;
                }

                synchronized (node) {
                    prev = node.vOpt;
                    if (prev == null || !shouldUpdate(func, prev, expected)) {
                        // nothing to do
                        return prev;
                    }
                    if (!attemptUnlink_nl(parent, node)) {
                        return SpecialRetry;
                    }
                }
                // try to fix the parent while we've still got the lock
                damaged = fixHeight_nl(parent);
            }
            fixHeightAndRebalance(damaged);
            return prev;
        } else {
            // potential update (including remove-without-unlink)
            synchronized (node) {
                // regular version changes don't bother us
                if (isUnlinked(node.shrinkOVL)) {
                    return SpecialRetry;
                }

                final Object prev = node.vOpt;
                if (!shouldUpdate(func, prev, expected)) {
                    return prev;
                }

                // retry if we now detect that unlink is possible
                if (newValue == null && (node.left == null || node.right == null)) {
                    return SpecialRetry;
                }

                // update in-place
                node.vOpt = newValue;
                return prev;
            }
        }
    }

    /** Does not adjust the size or any heights. */
    private boolean attemptUnlink_nl(final Node<K,V> parent, final Node<K,V> node) {
        // assert (Thread.holdsLock(parent));
        // assert (Thread.holdsLock(node));
        assert (!isUnlinked(parent.shrinkOVL));

        final Node<K,V> parentL = parent.left;
        final Node<K,V>  parentR = parent.right;
        if (parentL != node && parentR != node) {
            // node is no longer a child of parent
            return false;
        }

        assert (!isUnlinked(node.shrinkOVL));
        assert (parent == node.parent);

        final Node<K,V> left = node.unsharedLeft();
        final Node<K,V> right = node.unsharedRight();
        if (left != null && right != null) {
            // splicing is no longer possible
            return false; 
        }
        final Node<K,V> splice = left != null ? left : right;

        if (parentL == node) {
            parent.left = splice; 
        } else {
            parent.right = splice;
        }
        if (splice != null) {
            splice.parent = parent;
        }

        node.shrinkOVL = UnlinkedOVL;
        node.vOpt = null;

        return true;
    }

    //////////////// NavigableMap stuff

    public Map.Entry<K,V> pollFirstEntry() {
        return pollExtremeEntry(Left);
    }

    public Map.Entry<K,V> pollLastEntry() {
        return pollExtremeEntry(Right);
    }

    private Map.Entry<K,V> pollExtremeEntry(final char dir) {
        final Epoch.Ticket ticket = holderRef.beginMutation();
        int sizeDelta = 0;
        try {
            final Map.Entry<K,V> prev = pollExtremeEntryUnderRoot(dir, holderRef.mutable());
            if (prev != null) {
                sizeDelta = -1;
            }
            return prev;
        } finally {
            ticket.leave(sizeDelta);
        }
    }

    private Map.Entry<K,V> pollExtremeEntryUnderRoot(final char dir, final RootHolder<K,V> holder) {
        while (true) {
            final Node<K,V> right = holder.unsharedRight();
            if (right == null) {
                // tree is empty, nothing to remove
                return null;
            } else {
                final long ovl = right.shrinkOVL;
                if (isShrinkingOrUnlinked(ovl)) {
                    right.waitUntilShrinkCompleted(ovl);
                    // RETRY
                } else if (right == holder.right) {
                    // this is the protected .right
                    final Map.Entry<K,V> result = attemptRemoveExtreme(dir, holder, right, ovl);
                    if (result != SpecialRetry) {
                        return result;
                    }
                    // else RETRY
                }
            }
        }
    }

    private Map.Entry<K,V> attemptRemoveExtreme(final char dir,
                                                final Node<K,V> parent,
                                                final Node<K,V> node,
                                                final long nodeOVL) {
        assert (nodeOVL != UnlinkedOVL);

        while (true) {
            final Node<K,V> child = node.child(dir);

            if (nodeOVL != node.shrinkOVL) {
                return null;
            }

            if (child == null) {
                // potential unlink, get ready by locking the parent
                final Object vo;
                final Node<K,V> damaged;
                synchronized (parent) {
                    if (isUnlinked(parent.shrinkOVL) || node.parent != parent) {
                        return null;
                    }

                    synchronized (node) {
                        vo = node.vOpt;
                        if (node.child(dir) != null || !attemptUnlink_nl(parent, node)) {
                            return null;
                        }
                        // success!
                    }
                    // try to fix parent.height while we've still got the lock
                    damaged = fixHeight_nl(parent);
                }
                fixHeightAndRebalance(damaged);
                return new SimpleImmutableEntry<K,V>(node.key, decodeNull(vo));
            } else {
                // keep going down
                final long childOVL = child.shrinkOVL;
                if (isShrinkingOrUnlinked(childOVL)) {
                    child.waitUntilShrinkCompleted(childOVL);
                    // RETRY
                } else if (child != node.child(dir)) {
                    // this second read is important, because it is protected
                    // by childOVL
                    // RETRY
                } else {
                    // validate the read that our caller took to get to node
                    if (node.shrinkOVL != nodeOVL) {
                        return null;
                    }

                    final Map.Entry<K,V> result = attemptRemoveExtreme(dir, node, child, childOVL);
                    if (result != null) {
                        return result;
                    }
                    // else RETRY
                }
            }
        }
    }

    

    //////////////// tree balance and height info repair

    private static final int UnlinkRequired = -1;
    private static final int RebalanceRequired = -2;
    private static final int NothingRequired = -3;

    private int nodeCondition(final Node<K,V> node) {
        // Begin atomic.

        final Node<K,V> nL = node.left;
        final Node<K,V> nR = node.right;

        if ((nL == null || nR == null) && node.vOpt == null) {
            return UnlinkRequired;
        }

        final int hN = node.height;
        final int hL0 = height(nL);
        final int hR0 = height(nR);

        // End atomic.  Since any thread that changes a node promises to fix
        // it, either our read was consistent (and a NothingRequired conclusion
        // is correct) or someone else has taken responsibility for either node
        // or one of its children.

        final int hNRepl = 1 + Math.max(hL0, hR0);
        final int bal = hL0 - hR0;

        if (bal < -1 || bal > 1) {
            return RebalanceRequired;
        }

        return hN != hNRepl ? hNRepl : NothingRequired;
    }

    private void fixHeightAndRebalance(Node<K,V> node) {
        while (node != null && node.parent != null) {
            final int condition = nodeCondition(node);
            if (condition == NothingRequired || isUnlinked(node.shrinkOVL)) {
                // nothing to do, or no point in fixing this node
                return;
            }

            if (condition != UnlinkRequired && condition != RebalanceRequired) {
                synchronized (node) {
                    node = fixHeight_nl(node);
                }
            } else {
                final Node<K,V> nParent = node.parent;
                synchronized (nParent) {
                    if (!isUnlinked(nParent.shrinkOVL) && node.parent == nParent) {
                        synchronized (node) {
                            node = rebalance_nl(nParent, node);
                        }
                    }
                    // else RETRY
                }
            }
        }
    }

    /** Attempts to fix the height of a (locked) damaged node, returning the
     *  lowest damaged node for which this thread is responsible.  Returns null
     *  if no more repairs are needed.
     */
    private Node<K,V> fixHeight_nl(final Node<K,V> node) {
        final int c = nodeCondition(node);
        switch (c) {
            case RebalanceRequired:
            case UnlinkRequired:
                // can't repair
                return node;
            case NothingRequired:
                // Any future damage to this node is not our responsibility.
                return null;
            default:
                node.height = c;
                // we've damaged our parent, but we can't fix it now
                return node.parent;
        }
    }

    /** nParent and n must be locked on entry.  Returns a damaged node, or null
     *  if no more rebalancing is necessary.
     */
    private Node<K,V> rebalance_nl(final Node<K,V> nParent, final Node<K,V> n) {

        final Node<K,V> nL = n.unsharedLeft();
        final Node<K,V> nR = n.unsharedRight();

        if ((nL == null || nR == null) && n.vOpt == null) {
            if (attemptUnlink_nl(nParent, n)) {
                // attempt to fix nParent.height while we've still got the lock
                return fixHeight_nl(nParent);
            } else {
                // retry needed for n
                return n;
            }
        }

        final int hN = n.height;
        final int hL0 = height(nL);
        final int hR0 = height(nR);
        final int hNRepl = 1 + Math.max(hL0, hR0);
        final int bal = hL0 - hR0;

        if (bal > 1) {
            return rebalanceToRight_nl(nParent, n, nL, hR0);
        } else if (bal < -1) {
            return rebalanceToLeft_nl(nParent, n, nR, hL0);
        } else if (hNRepl != hN) {
            // we've got more than enough locks to do a height change, no need to
            // trigger a retry
            n.height = hNRepl;

            // nParent is already locked, let's try to fix it too
            return fixHeight_nl(nParent);
        } else {
            // nothing to do
            return null;
        }
    }

    private Node<K,V> rebalanceToRight_nl(final Node<K,V> nParent,
                                          final Node<K, V> n,
                                          final Node<K, V> nL,
                                          final int hR0) {
        // L is too large, we will rotate-right.  If L.R is taller
        // than L.L, then we will first rotate-left L.
        synchronized (nL) {
            final int hL = nL.height;
            if (hL - hR0 <= 1) {
                return n; // retry
            } else {
                final Node<K, V> nLR = nL.unsharedRight();
                final int hLL0 = height(nL.left);
                final int hLR0 = height(nLR);
                if (hLL0 >= hLR0) {
                    // rotate right based on our snapshot of hLR
                    return rotateRight_nl(nParent, n, nL, hR0, hLL0, nLR, hLR0);
                } else {
                    synchronized (nLR) {
                        // If our hLR snapshot is incorrect then we might
                        // actually need to do a single rotate-right on n.
                        final int hLR = nLR.height;
                        if (hLL0 >= hLR) {
                            return rotateRight_nl(nParent, n, nL, hR0, hLL0, nLR, hLR);
                        } else {
                            // If the underlying left balance would not be
                            // sufficient to actually fix n.left, then instead
                            // of rolling it into a double rotation we do it on
                            // it's own.  This may let us avoid rotating n at
                            // all, but more importantly it avoids the creation
                            // of damaged nodes that don't have a direct
                            // ancestry relationship.  The recursive call to
                            // rebalanceToRight_nl in this case occurs after we
                            // release the lock on nLR.
                            final int hLRL = height(nLR.left);
                            final int b = hLL0 - hLRL;
                            if (b >= -1 && b <= 1) {
                                // nParent.child.left won't be damaged after a double rotation
                                return rotateRightOverLeft_nl(nParent, n, nL, hR0, hLL0, nLR, hLRL);
                            }
                        }
                    }
                    // focus on nL, if necessary n will be balanced later   
                    return rebalanceToLeft_nl(n, nL, nLR, hLL0);
                }
            }
        }
    }

    private Node<K,V> rebalanceToLeft_nl(final Node<K,V> nParent,
                                         final Node<K, V> n,
                                         final Node<K, V> nR,
                                         final int hL0) {
        synchronized (nR) {
            final int hR = nR.height;
            if (hL0 - hR >= -1) {
                return n; // retry
            } else {
                final Node<K,V> nRL = nR.unsharedLeft();
                final int hRL0 = height(nRL);
                final int hRR0 = height(nR.right);
                if (hRR0 >= hRL0) {
                    return rotateLeft_nl(nParent, n, hL0, nR, nRL, hRL0, hRR0);
                } else {
                    synchronized (nRL) {
                        final int hRL = nRL.height;
                        if (hRR0 >= hRL) {
                            return rotateLeft_nl(nParent, n, hL0, nR, nRL, hRL, hRR0);
                        } else {
                            final int hRLR = height(nRL.right);
                            final int b = hRR0 - hRLR;
                            if (b >= -1 && b <= 1) {
                                return rotateLeftOverRight_nl(nParent, n, hL0, nR, nRL, hRR0, hRLR);
                            }
                        }
                    }
                    return rebalanceToRight_nl(n, nR, nRL, hRR0);
                }
            }
        }
    }

    private Node<K,V> rotateRight_nl(final Node<K,V> nParent,
                                     final Node<K, V> n,
                                     final Node<K, V> nL,
                                     final int hR,
                                     final int hLL,
                                     final Node<K,V> nLR,
                                     final int hLR) {
        final long nodeOVL = n.shrinkOVL;

        final Node<K,V> nPL = nParent.left;

        n.shrinkOVL = beginChange(nodeOVL);

        n.left = nLR;
        if (nLR != null) {
            nLR.parent = n;
        }

        nL.right = n;
        n.parent = nL;

        if (nPL == n) {
            nParent.left = nL;
        } else {
            nParent.right = nL;
        }
        nL.parent = nParent;

        // fix up heights links
        final int hNRepl = 1 + Math.max(hLR, hR);
        n.height = hNRepl;
        nL.height = 1 + Math.max(hLL, hNRepl);

        n.shrinkOVL = endChange(nodeOVL);

        // We have damaged nParent, n (now parent.child.right), and nL (now
        // parent.child).  n is the deepest.  Perform as many fixes as we can
        // with the locks we've got.

        // We've already fixed the height for n, but it might still be outside
        // our allowable balance range.  In that case a simple fixHeight_nl
        // won't help.
        final int balN = hLR - hR;
        if (balN < -1 || balN > 1) {
            // we need another rotation at n
            return n;
        }

        // we've already fixed the height at nL, do we need a rotation here?
        final int balL = hLL - hNRepl;
        if (balL < -1 || balL > 1) {
            return nL;
        }

        // try to fix the parent height while we've still got the lock
        return fixHeight_nl(nParent);
    }

    private Node<K,V> rotateLeft_nl(final Node<K,V> nParent,
                                    final Node<K, V> n,
                                    final int hL,
                                    final Node<K, V> nR,
                                    final Node<K, V> nRL,
                                    final int hRL,
                                    final int hRR) {
        final long nodeOVL = n.shrinkOVL;

        final Node<K,V> nPL = nParent.left;

        n.shrinkOVL = beginChange(nodeOVL);

        // fix up n links, careful to be compatible with concurrent traversal for all but n
        n.right = nRL;
        if (nRL != null) {
            nRL.parent = n;
        }

        nR.left = n;
        n.parent = nR;

        if (nPL == n) {
            nParent.left = nR;
        } else {
            nParent.right = nR;
        }
        nR.parent = nParent;

        // fix up heights
        final int  hNRepl = 1 + Math.max(hL, hRL);
        n.height = hNRepl;
        nR.height = 1 + Math.max(hNRepl, hRR);

        n.shrinkOVL = endChange(nodeOVL);

        final int balN = hRL - hL;
        if (balN < -1 || balN > 1) {
            return n;
        }

        final int balR = hRR - hNRepl;
        if (balR < -1 || balR > 1) {
            return nR;
        }

        return fixHeight_nl(nParent);
    }

    private Node<K,V> rotateRightOverLeft_nl(final Node<K,V> nParent,
                                             final Node<K, V> n,
                                             final Node<K, V> nL,
                                             final int hR,
                                             final int hLL,
                                             final Node<K,V> nLR,
                                             final int hLRL) {
        final long nodeOVL = n.shrinkOVL;
        final long leftOVL = nL.shrinkOVL;

        final Node<K,V> nPL = nParent.left;
        final Node<K,V> nLRL = nLR.unsharedLeft();
        final Node<K,V> nLRR = nLR.unsharedRight();
        final int hLRR = height(nLRR);

        n.shrinkOVL = beginChange(nodeOVL);
        nL.shrinkOVL = beginChange(leftOVL);

        // fix up n links, careful about the order!
        n.left = nLRR;
        if (nLRR != null) {
            nLRR.parent = n;
        }

        nL.right = nLRL;
        if (nLRL != null) {
            nLRL.parent = nL;
        }

        nLR.left = nL;
        nL.parent = nLR;
        nLR.right = n;
        n.parent = nLR;

        if (nPL == n) {
            nParent.left = nLR;
        } else {
            nParent.right = nLR;
        }
        nLR.parent = nParent;

        // fix up heights
        final int hNRepl = 1 + Math.max(hLRR, hR);
        n.height = hNRepl;
        final int hLRepl = 1 + Math.max(hLL, hLRL);
        nL.height = hLRepl;
        nLR.height = 1 + Math.max(hLRepl, hNRepl);

        n.shrinkOVL = endChange(nodeOVL);
        nL.shrinkOVL = endChange(leftOVL);

        // caller should have performed only a single rotation if nL was going
        // to end up damaged
        assert(Math.abs(hLL - hLRL) <= 1);

        // We have damaged nParent, nLR (now parent.child), and n (now
        // parent.child.right).  n is the deepest.  Perform as many fixes as we
        // can with the locks we've got.

        // We've already fixed the height for n, but it might still be outside
        // our allowable balance range.  In that case a simple fixHeight_nl
        // won't help.
        final int balN = hLRR - hR;
        if (balN < -1 || balN > 1) {
            // we need another rotation at n
            return n;
        }

        // we've already fixed the height at nLR, do we need a rotation here?
        final int balLR = hLRepl - hNRepl;
        if (balLR < -1 || balLR > 1) {
            return nLR;
        }

        // try to fix the parent height while we've still got the lock
        return fixHeight_nl(nParent);         
    }

    private Node<K,V> rotateLeftOverRight_nl(final Node<K,V> nParent,
                                             final Node<K, V> n,
                                             final int hL,
                                             final Node<K, V> nR,
                                             final Node<K,V> nRL,
                                             final int hRR,
                                             final int hRLR) {
        final long nodeOVL = n.shrinkOVL;
        final long rightOVL = nR.shrinkOVL;

        final Node<K,V> nPL = nParent.left;
        final Node<K,V> nRLL = nRL.unsharedLeft();
        final Node<K,V> nRLR = nRL.unsharedRight();
        final int hRLL = height(nRLL);

        n.shrinkOVL = beginChange(nodeOVL);
        nR.shrinkOVL = beginChange(rightOVL);

        // fix up n links, careful about the order!
        n.right = nRLL;
        if (nRLL != null) {
            nRLL.parent = n;
        }

        nR.left = nRLR;
        if (nRLR != null) {
            nRLR.parent = nR;
        }

        nRL.right = nR;
        nR.parent = nRL;
        nRL.left = n;
        n.parent = nRL;

        if (nPL == n) {
            nParent.left = nRL;
        } else {
            nParent.right = nRL;
        }
        nRL.parent = nParent;

        // fix up heights
        final int hNRepl = 1 + Math.max(hL, hRLL);
        n.height = hNRepl;
        final int hRRepl = 1 + Math.max(hRLR, hRR);
        nR.height = hRRepl;
        nRL.height = 1 + Math.max(hNRepl, hRRepl);

        n.shrinkOVL = endChange(nodeOVL);
        nR.shrinkOVL = endChange(rightOVL);

        assert(Math.abs(hRR - hRLR) <= 1);

        final int balN = hRLL - hL;
        if (balN < -1 || balN > 1) {
            return n;
        }
        final int balRL = hRRepl - hNRepl;
        if (balRL < -1 || balRL > 1) {
            return nRL;
        }
        return fixHeight_nl(nParent);
    }

    //////////////// Map views

    @Override
    public NavigableSet<K> keySet() {
        return navigableKeySet();
    }

    @Override
    public Set<Map.Entry<K,V>> entrySet() {
        return new EntrySet();
    }

    private class EntrySet extends AbstractSet<Map.Entry<K,V>> {

        @Override
        public int size() {
            return SnapTreeMap.this.size();
        }

        @Override
        public boolean isEmpty() {
            return SnapTreeMap.this.isEmpty();
        }

        @Override
        public void clear() {
            SnapTreeMap.this.clear();
        }

        @Override
        public boolean contains(final Object o) {
            if (!(o instanceof Map.Entry<?,?>)) {
                return false;
            }
            final Object k = ((Map.Entry<?,?>)o).getKey();
            final Object v = ((Map.Entry<?,?>)o).getValue();
            final Object actualVo = SnapTreeMap.this.getImpl(k);
            if (actualVo == null) {
                // no associated value
                return false;
            }
            final V actual = decodeNull(actualVo);
            return v == null ? actual == null : v.equals(actual);
        }

        @Override
        public boolean add(final Entry<K,V> e) {
            final Object v = encodeNull(e.getValue());
            return update(e.getKey(), UpdateAlways, null, v) != v;
        }

        @Override
        public boolean remove(final Object o) {
            if (!(o instanceof Map.Entry<?,?>)) {
                return false;
            }
            final Object k = ((Map.Entry<?,?>)o).getKey();
            final Object v = ((Map.Entry<?,?>)o).getValue();
            return SnapTreeMap.this.remove(k, v);
        }

        @Override
        public Iterator<Entry<K,V>> iterator() {
            return new EntryIter<K,V>(SnapTreeMap.this);
        }
    }

    private static class EntryIter<K,V> extends AbstractIter<K,V> implements Iterator<Map.Entry<K,V>> {
        private EntryIter(final SnapTreeMap<K,V> m) {
            super(m);
        }

        private EntryIter(final SnapTreeMap<K,V> m,
                          final Comparable<? super K> minCmp,
                          final boolean minIncl,
                          final Comparable<? super K> maxCmp,
                          final boolean maxIncl,
                          final boolean descending) {
            super(m, minCmp, minIncl, maxCmp, maxIncl, descending);
        }

        public Entry<K,V> next() {
            return nextNode();
        }
    }

    private static class KeyIter<K,V> extends AbstractIter<K,V> implements Iterator<K> {
        private KeyIter(final SnapTreeMap<K,V> m) {
            super(m);
        }

        private KeyIter(final SnapTreeMap<K,V> m,
                        final Comparable<? super K> minCmp,
                        final boolean minIncl,
                        final Comparable<? super K> maxCmp,
                        final boolean maxIncl,
                        final boolean descending) {
            super(m, minCmp, minIncl, maxCmp, maxIncl, descending);
        }

        public K next() {
            return nextNode().key;
        }
    }

    private static class AbstractIter<K,V> {
        private final SnapTreeMap<K,V> m;
        private final boolean descending;
        private final char forward;
        private final char reverse;
        private Node<K,V>[] path;
        private int depth = 0;
        private Node<K,V> mostRecentNode;
        private K endKey;

        @SuppressWarnings("unchecked")
        AbstractIter(final SnapTreeMap<K,V> m) {
            this.m = m;
            this.descending = false;
            this.forward = Right;
            this.reverse = Left;
            final Node<K,V> root = m.holderRef.frozen().right;
            this.path = (Node<K,V>[]) new Node[1 + height(root)];
            this.endKey = null;
            pushFirst(root);
        }

        AbstractIter(final SnapTreeMap<K,V> m,
                     final Comparable<? super K> minCmp,
                     final boolean minIncl,
                     final Comparable<? super K> maxCmp,
                     final boolean maxIncl,
                     final boolean descending) {
            this.m = m;
            this.descending = descending;
            this.forward = !descending ? Right : Left;
            this.reverse = !descending ? Left : Right;
            final Comparable<? super K> fromCmp;
            final boolean fromIncl = !descending ? minIncl : maxIncl;
            final Comparable<? super K> toCmp;
            final boolean toIncl = !descending ? maxIncl : minIncl;
            if (!descending) {
                fromCmp = minCmp;
                toCmp = maxCmp;
            } else {
                fromCmp = maxCmp;
                toCmp = minCmp;
            }

            final Node<K,V> root = m.holderRef.frozen().right;

            if (toCmp != null) {
                this.endKey = (K) m.boundedExtreme(minCmp, minIncl, maxCmp, maxIncl, true, forward);
                if (this.endKey == null) {
                    // no node satisfies the bound, nothing to iterate
                    // ---------> EARLY EXIT
                    return;
                }
            } else {
                this.endKey = null;
            }

            this.path = (Node<K,V>[]) new Node[1 + height(root)];

            if (fromCmp == null) {
                pushFirst(root);
            }
            else {
                pushFirst(root, fromCmp, fromIncl);
                if (top().vOpt == null) {
                    advance();
                }
            }
        }

        private int cmp(final Comparable<? super K> comparable, final K key) {
            final int c = comparable.compareTo(key);
            if (!descending) {
                return c;
            } else {
                return c == Integer.MIN_VALUE ? 1 : -c;
            }
        }

        private void pushFirst(Node<K,V> node) {
            while (node != null) {
                path[depth++] = node;
                node = node.child(reverse);
            }
        }

        private void pushFirst(Node<K,V> node, final Comparable<? super K> fromCmp, final boolean fromIncl) {
            while (node != null) {
                final int c = cmp(fromCmp, node.key);
                if (c < 0 || (c == 0 && !fromIncl)) {
                    // everything we're interested in is on the right
                    node = node.child(forward);
                }
                else {
                    path[depth++] = node;
                    if (c == 0) {
                        // start the iteration here
                        return;
                    }
                    else {
                        node = node.child(reverse);
                    }
                }
            }
        }

        private Node<K,V> top() {
            return path[depth - 1];
        }

        private void advance() {
            do {
                final Node<K,V> fwd = top().child(forward);
                if (fwd != null) {
                    pushFirst(fwd);
                } else {
                    // keep going up until we pop a node that is a left child
                    Node<K,V> popped;
                    do {
                        popped = path[--depth];
                    } while (depth > 0 && popped == top().child(forward));
                }

                if (endKey != null && endKey == top().key) {
                    depth = 0;
                }
                if (depth == 0) {
                    // clear out the path so we don't pin too much stuff
                    path = null;
                    return;
                }

                // skip removed-but-not-unlinked entries
            } while (top().vOpt == null);
        }

        public boolean hasNext() {
            return depth > 0;
        }

        Node<K,V> nextNode() {
            if (depth == 0) {
                throw new NoSuchElementException();
            }
            mostRecentNode = top();
            advance();
            return mostRecentNode;
        }

        public void remove() {
            if (mostRecentNode == null) {
                throw new IllegalStateException();
            }
            m.remove(mostRecentNode.key);
            mostRecentNode = null;
        }
    }

    //////////////// navigable keySet

    @Override
    public NavigableSet<K> navigableKeySet() {
        return new KeySet<K>(this) {
            public Iterator<K> iterator() {
                return new KeyIter<K,V>(SnapTreeMap.this);
            }
        };
    }

    @Override
    public NavigableSet<K> descendingKeySet() {
        return descendingMap().navigableKeySet();
    }

    private abstract static class KeySet<K> extends AbstractSet<K> implements NavigableSet<K> {

        private final ConcurrentNavigableMap<K,?> map;

        protected KeySet(final ConcurrentNavigableMap<K,?> map) {
            this.map = map;
        }

        //////// basic Set stuff

        abstract public Iterator<K> iterator();

        @Override
        public boolean contains(final Object o) { return map.containsKey(o); }
        public int size() { return map.size(); }
        @Override
        public boolean remove(final Object o) { return map.remove(o) != null; }

        //////// SortedSet stuff

        public Comparator<? super K> comparator() { return map.comparator(); }
        public K first() { return map.firstKey(); }
        public K last() { return map.lastKey(); }

        //////// NavigableSet stuff

        public K lower(final K k) { return map.lowerKey(k); }
        public K floor(final K k) { return map.floorKey(k); }
        public K ceiling(final K k) { return map.ceilingKey(k); }
        public K higher(final K k) { return map.higherKey(k); }

        public K pollFirst() { return map.pollFirstEntry().getKey(); }
        public K pollLast() { return map.pollLastEntry().getKey(); }

        public NavigableSet<K> descendingSet() { return map.descendingKeySet(); }
        public Iterator<K> descendingIterator() { return map.descendingKeySet().iterator(); }

        public NavigableSet<K> subSet(final K fromElement, final boolean minInclusive, final K toElement, final boolean maxInclusive) {
            return map.subMap(fromElement, minInclusive, toElement, maxInclusive).keySet();
        }
        public NavigableSet<K> headSet(final K toElement, final boolean inclusive) {
            return map.headMap(toElement, inclusive).keySet();
        }
        public NavigableSet<K> tailSet(final K fromElement, final boolean inclusive) {
            return map.tailMap(fromElement, inclusive).keySet();
        }
        public SortedSet<K> subSet(final K fromElement, final K toElement) {
            return map.subMap(fromElement, toElement).keySet();
        }
        public SortedSet<K> headSet(final K toElement) {
            return map.headMap(toElement).keySet();
        }
        public SortedSet<K> tailSet(final K fromElement) {
            return map.tailMap(fromElement).keySet();
        }
    }

    //////////////// NavigableMap views

    @Override
    public ConcurrentNavigableMap<K,V> subMap(final K fromKey,
                                              final boolean fromInclusive,
                                              final K toKey,
                                              final boolean toInclusive) {
        return new SubMap(this, comparable(fromKey), fromInclusive, comparable(toKey), toInclusive, false);
    }

    @Override
    public ConcurrentNavigableMap<K,V> headMap(final K toKey, final boolean inclusive) {
        return new SubMap(this, null, false, comparable(toKey), inclusive, false);
    }

    @Override
    public ConcurrentNavigableMap<K,V> tailMap(final K fromKey, final boolean inclusive) {
        return new SubMap(this, comparable(fromKey), inclusive, null, false, false);
    }

    @Override
    public ConcurrentNavigableMap<K,V> subMap(final K fromKey, final K toKey) {
        return subMap(fromKey, true, toKey, false);
    }

    @Override
    public ConcurrentNavigableMap<K,V> headMap(final K toKey) {
        return headMap(toKey, false);
    }

    @Override
    public ConcurrentNavigableMap<K,V> tailMap(final K fromKey) {
        return tailMap(fromKey, true);
    }

    @Override
    public ConcurrentNavigableMap<K,V> descendingMap() {
        return new SubMap(this, null, false, null, false, true);
    }

    private static class SubMap<K,V> extends AbstractMap<K,V> implements ConcurrentNavigableMap<K,V>, Cloneable {

        private final SnapTreeMap<K,V> m;
        private final Comparable<? super K> minCmp;
        private final boolean minIncl;
        private final Comparable<? super K> maxCmp;
        private final boolean maxIncl;
        private final boolean descending;

        private SubMap(final SnapTreeMap<K, V> m,
                       final Comparable<? super K> minCmp,
                       final boolean minIncl,
                       final Comparable<? super K> maxCmp,
                       final boolean maxIncl,
                       final boolean descending) {
            this.m = m;
            this.minCmp = minCmp;
            this.minIncl = minIncl;
            this.maxCmp = maxCmp;
            this.maxIncl = maxIncl;
            this.descending = descending;
        }

        private boolean tooLow(final K key) {
            if (minCmp == null) {
                return false;
            } else {
                final int c = minCmp.compareTo(key);
                return c < 0 || (c == 0 && !minIncl);
            }
        }

        private boolean tooHigh(final K key) {
            if (maxCmp == null) {
                return false;
            } else {
                final int c = maxCmp.compareTo(key);
                return c > 0 || (c == 0 && !maxIncl);
            }
        }

        private boolean inRange(final K key) {
            return !tooLow(key) && !tooHigh(key);
        }

        private void requireInRange(final K key) {
            if (key == null) {
                throw new NullPointerException();
            }
            if (!inRange(key)) {
                throw new IllegalArgumentException();
            }
        }

        private char minDir() {
            return descending ? Right : Left;
        }

        private char maxDir() {
            return descending ? Left : Right;
        }

        //////// AbstractMap

        @Override
        public boolean containsKey(final Object key) {
            if (key == null) {
                throw new NullPointerException();
            }
            final K k = (K) key;
            return inRange(k) && m.containsKey(k);
        }

        @Override
        public Set<Entry<K,V>> entrySet() {
            return new EntrySubSet();
        }

        private class EntrySubSet extends AbstractSet<Map.Entry<K,V>> {
            public int size() {
                return SubMap.this.size();
            }

            @Override
            public boolean isEmpty() {
                return SubMap.this.isEmpty();
            }

            @Override
            public boolean contains(final Object o) {
                if (!(o instanceof Map.Entry<?,?>)) {
                    return false;
                }
                final Object k = ((Map.Entry<?,?>)o).getKey();
                if (!inRange((K) k)) {
                    return false;
                }
                final Object v = ((Map.Entry<?,?>)o).getValue();
                final Object actualVo = m.getImpl(k);
                if (actualVo == null) {
                    // no associated value
                    return false;
                }
                final V actual = m.decodeNull(actualVo);
                return v == null ? actual == null : v.equals(actual);
            }

            @Override
            public boolean add(final Entry<K,V> e) {
                requireInRange(e.getKey());
                final Object v = encodeNull(e.getValue());
                return m.update(e.getKey(), UpdateAlways, null, v) != v;
            }

            @Override
            public boolean remove(final Object o) {
                if (!(o instanceof Map.Entry<?,?>)) {
                    return false;
                }
                final Object k = ((Map.Entry<?,?>)o).getKey();
                final Object v = ((Map.Entry<?,?>)o).getValue();
                return SubMap.this.remove(k, v);
            }

            @Override
            public Iterator<Entry<K,V>> iterator() {
                return new EntryIter(m, minCmp, minIncl, maxCmp, maxIncl, descending);
            }
        }

        //////// SortedMap

        @Override
        public Comparator<? super K> comparator() {
            final Comparator<? super K> fromM = m.comparator();
            if (descending) {
                return Collections.reverseOrder(fromM);
            } else {
                return fromM;
            }
        }

        @Override
        public K firstKey() {
            return (K) m.boundedExtremeOrThrow(minCmp, minIncl, maxCmp, maxIncl, true, minDir());
        }

        @Override
        public K lastKey() {
            return (K) m.boundedExtremeOrThrow(minCmp, minIncl, maxCmp, maxIncl, true, maxDir());
        }

        //////// NavigableMap

        @Override
        public Entry<K,V> lowerEntry(final K key) {
            if (!descending) {
                return (Entry<K,V>) m.boundedExtreme(minCmp, minIncl, m.comparable(key), false, false, Right);
            } else {
                return (Entry<K,V>) m.boundedExtreme(m.comparable(key), false, maxCmp, maxIncl, false, Left);
            }
        }

        @Override
        public K lowerKey(final K key) {
            if (!descending) {
                return (K) m.boundedExtreme(minCmp, minIncl, m.comparable(key), false, true, Right);
            } else {
                return (K) m.boundedExtreme(m.comparable(key), false, maxCmp, maxIncl, true, Left);
            }
        }

        @Override
        public Entry<K,V> floorEntry(final K key) {
            if (!descending) {
                return (Entry<K,V>) m.boundedExtreme(minCmp, minIncl, m.comparable(key), true, false, Right);
            } else {
                return (Entry<K,V>) m.boundedExtreme(m.comparable(key), true, maxCmp, maxIncl, false, Left);
            }
        }

        @Override
        public K floorKey(final K key) {
            if (!descending) {
                return (K) m.boundedExtreme(minCmp, minIncl, m.comparable(key), true, true, Right);
            } else {
                return (K) m.boundedExtreme(m.comparable(key), true, maxCmp, maxIncl, true, Left);
            }
        }

        @Override
        public Entry<K,V> ceilingEntry(final K key) {
            if (!descending) {
                return (Entry<K,V>) m.boundedExtreme(m.comparable(key), true, maxCmp, maxIncl, false, Left);
            } else {
                return (Entry<K,V>) m.boundedExtreme(minCmp, minIncl, m.comparable(key), true, false, Right);
            }
        }

        @Override
        public K ceilingKey(final K key) {
            if (!descending) {
                return (K) m.boundedExtreme(m.comparable(key), true, maxCmp, maxIncl, true, Left);
            } else {
                return (K) m.boundedExtreme(minCmp, minIncl, m.comparable(key), true, true, Right);
            }
        }

        @Override
        public Entry<K,V> higherEntry(final K key) {
            if (!descending) {
                return (Entry<K,V>) m.boundedExtreme(m.comparable(key), false, maxCmp, maxIncl, false, Left);
            } else {
                return (Entry<K,V>) m.boundedExtreme(minCmp, minIncl, m.comparable(key), false, false, Right);
            }
        }

        @Override
        public K higherKey(final K key) {
            if (!descending) {
                return (K) m.boundedExtreme(m.comparable(key), false, maxCmp, maxIncl, true, Left);
            } else {
                return (K) m.boundedExtreme(minCmp, minIncl, m.comparable(key), false, true, Right);
            }
        }

        @Override
        public Entry<K,V> firstEntry() {
            return (Entry<K,V>) m.boundedExtremeOrThrow(minCmp, minIncl, maxCmp, maxIncl, false, minDir());
        }

        @Override
        public Entry<K,V> lastEntry() {
            return (Entry<K,V>) m.boundedExtremeOrThrow(minCmp, minIncl, maxCmp, maxIncl, false, maxDir());
        }

        @Override
        public Entry<K,V> pollFirstEntry() {
            while (true) {
                final Entry<K,V> snapshot = (Entry<K, V>) m.boundedExtreme(minCmp, minIncl, maxCmp, maxIncl, false, minDir());
                if (snapshot == null || m.remove(snapshot.getKey(), snapshot.getValue())) {
                    return snapshot;
                }
            }
        }

        @Override
        public Entry<K, V> pollLastEntry() {
            while (true) {
                final Entry<K,V> snapshot = (Entry<K, V>) m.boundedExtreme(minCmp, minIncl, maxCmp, maxIncl, false, maxDir());
                if (snapshot == null || m.remove(snapshot.getKey(), snapshot.getValue())) {
                    return snapshot;
                }
            }
        }

        //////// ConcurrentMap

        @Override
        public V putIfAbsent(final K key, final V value) {
            requireInRange(key);
            return m.putIfAbsent(key, value);
        }

        @Override
        public boolean remove(final Object key, final Object value) {
            return inRange((K) key) && m.remove(key, value);
        }

        @Override
        public boolean replace(final K key, final V oldValue, final V newValue) {
            requireInRange(key);
            return m.replace(key, oldValue, newValue);
        }

        @Override
        public V replace(final K key, final V value) {
            requireInRange(key);
            return m.replace(key, value);
        }

        //////// ConcurrentNavigableMap

        @Override
        public ConcurrentNavigableMap<K,V> subMap(final K fromKey,
                                                  final boolean fromInclusive,
                                                  final K toKey,
                                                  final boolean toInclusive) {
            if (fromKey == null || toKey == null) {
                throw new NullPointerException();
            }
            return subMapImpl(fromKey, fromInclusive, toKey, toInclusive);
        }

        @Override
        public ConcurrentNavigableMap<K,V> headMap(final K toKey, final boolean inclusive) {
            if (toKey == null) {
                throw new NullPointerException();
            }
            return subMapImpl(null, false, toKey, inclusive);
        }

        @Override
        public ConcurrentNavigableMap<K,V> tailMap(final K fromKey, final boolean inclusive) {
            if (fromKey == null) {
                throw new NullPointerException();
            }
            return subMapImpl(fromKey, inclusive, null, false);
        }

        @Override
        public ConcurrentNavigableMap<K,V> subMap(final K fromKey, final K toKey) {
            return subMap(fromKey, true, toKey, false);
        }

        @Override
        public ConcurrentNavigableMap<K,V> headMap(final K toKey) {
            return headMap(toKey, false);
        }

        @Override
        public ConcurrentNavigableMap<K,V> tailMap(final K fromKey) {
            return tailMap(fromKey, true);
        }

        private ConcurrentNavigableMap<K,V> subMapImpl(final K fromKey,
                                                       final boolean fromIncl,
                                                       final K toKey,
                                                       final boolean toIncl) {
            final K extraMinKey = !descending ? fromKey : toKey;
            final boolean extraMinIncl = !descending ? fromIncl : toIncl;
            final K extraMaxKey = !descending ? toKey : fromKey;
            final boolean extraMaxIncl = !descending ? toIncl : fromIncl;

            Comparable<? super K> newMinCmp = minCmp;
            boolean newMinIncl = minIncl;
            if (extraMinKey != null) {
                final int c = minCmp == null ? 1 : minCmp.compareTo(extraMinKey);
                if (c > 0) {
                    newMinCmp = m.comparable(extraMinKey);
                    newMinIncl = extraMinIncl;
                }
                else if (c == 0 && !extraMinIncl) {
                    newMinIncl = false;
                }
            }

            Comparable<? super K> newMaxCmp = maxCmp;
            boolean newMaxIncl = maxIncl;
            if (extraMaxKey != null) {
                final int c = maxCmp == null ? -1 : maxCmp.compareTo(extraMaxKey);
                if (c < 0) {
                    newMaxCmp = m.comparable(extraMaxKey);
                    newMaxIncl = extraMaxIncl;
                }
                else if (c == 0 && !extraMaxIncl) {
                    newMaxIncl = false;
                }
            }

            return new SubMap(m, newMinCmp, newMinIncl, newMaxCmp, newMaxIncl, descending);
        }

        @Override
        public ConcurrentNavigableMap<K,V> descendingMap() {
            return new SubMap(m, maxCmp, maxIncl, minCmp, minIncl, !descending);
        }

        @Override
        public NavigableSet<K> keySet() {
            return navigableKeySet();
        }

        @Override
        public NavigableSet<K> navigableKeySet() {
            return new KeySet<K>(m) {
                public Iterator<K> iterator() {
                    return new KeyIter(m, minCmp, minIncl, maxCmp, maxIncl, descending);
                }
            };
        }

        @Override
        public NavigableSet<K> descendingKeySet() {
            return descendingMap().navigableKeySet();
        }
    }
}
