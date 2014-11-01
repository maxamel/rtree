package com.github.davidmoten.rtree;

import static com.github.davidmoten.rtree.geometry.Geometries.rectangle;
import static com.google.common.base.Optional.absent;
import static com.google.common.base.Optional.of;

import java.util.List;

import rx.Observable;
import rx.functions.Func1;
import rx.functions.Func2;

import com.github.davidmoten.rtree.geometry.Geometry;
import com.github.davidmoten.rtree.geometry.Point;
import com.github.davidmoten.rtree.geometry.Rectangle;
import com.github.davidmoten.rx.operators.OperatorBoundedPriorityQueue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;

/**
 * Immutable in-memory 2D R-Tree with configurable splitter heuristic.
 * 
 * @param <T>
 *            the entry type
 */
public final class RTree<T> {

    private final Optional<? extends Node<T>> root;
    private final Context context;

    /**
     * Benchmarks show that this is a good choice for up to O(10,000) entries
     * when using Quadratic splitter (Guttman).
     */
    public static final int MAX_CHILDREN_DEFAULT_GUTTMAN = 4;

    /**
     * Benchmarks show that this is the sweet spot for up to O(10,000) entries
     * when using R*-tree heuristics.
     */
    public static final int MAX_CHILDREN_DEFAULT_STAR = 4;

    private int size;

    /**
     * Constructor.
     * 
     * @param root
     *            the root node of the tree if present
     * @param context
     *            options for the R-tree
     */
    private RTree(Optional<? extends Node<T>> root, int size, Context context) {
        this.root = root;
        this.size = size;
        this.context = context;
    }

    /**
     * Constructor.
     * 
     * @param root
     *            the root node of the R-tree
     * @param context
     *            options for the R-tree
     */
    private RTree(Node<T> root, int size, Context context) {
        this(of(root), size, context);
    }

    /**
     * Constructor.
     * 
     * @param context
     *            specifies parameters and behaviour for the R-tree
     */
    public RTree(Context context) {
        this(Optional.<Node<T>> absent(), 0, context);
    }

    /**
     * Returns a new Builder instance for {@link RTree}. Defaults to
     * maxChildren=128, minChildren=64, splitter=QuadraticSplitter.
     *
     * @param <R>
     *            the value type of the entries in the tree
     * @return a new RTree instance
     */
    public static <R> RTree<R> create() {
        return new Builder().create();
    }

    /**
     * The tree is scanned for depth and the depth returned. This involves
     * recursing down to the leaf level of the tree to get the current depth.
     * Should be <code>log(n)</code> in complexity.
     * 
     * @return depth of the R-tree
     */
    public int calculateDepth() {
        return calculateDepth(root);
    }

    private static <T> int calculateDepth(Optional<? extends Node<T>> root) {
        if (!root.isPresent())
            return 0;
        else
            return calculateDepth(root.get(), 0);
    }

    private static <T> int calculateDepth(Node<T> node, int depth) {
        if (node instanceof Leaf)
            return depth + 1;
        else
            return calculateDepth(((NonLeaf<T>) node).children().get(0), depth + 1);
    }

    /**
     * When the number of children in an R-tree node drops below this number the
     * node is deleted and the children are added on to the R-tree again.
     * 
     * @param minChildren
     *            less than this number of children in a node triggers a node
     *            deletion and redistribution of its members
     * @return builder
     */
    public static Builder minChildren(int minChildren) {
        return new Builder().minChildren(minChildren);
    }

    /**
     * Sets the max number of children in an R-tree node.
     * 
     * @param maxChildren
     *            max number of children in an R-tree node
     * @return builder
     */
    public static Builder maxChildren(int maxChildren) {
        return new Builder().maxChildren(maxChildren);
    }

    /**
     * Sets the {@link Splitter} to use when maxChildren is reached.
     * 
     * @param splitter
     *            the splitter algorithm to use
     * @return builder
     */
    public static Builder splitter(Splitter splitter) {
        return new Builder().splitter(splitter);
    }

    /**
     * Sets the node {@link Selector} which decides which branches to follow
     * when inserting or searching.
     * 
     * @param selector
     *            determines which branches to follow when inserting or
     *            searching
     * @return builder
     */
    public static Builder selector(Selector selector) {
        return new Builder().selector(selector);
    }

    /**
     * Sets the splitter to {@link SplitterRStar} and selector to
     * {@link SelectorRStar} and defaults to minChildren=10.
     * 
     * @return builder
     */
    public static Builder star() {
        return new Builder().star();
    }

    /**
     * RTree Builder.
     */
    public static class Builder {

        /**
         * According to
         * http://dbs.mathematik.uni-marburg.de/publications/myPapers
         * /1990/BKSS90.pdf (R*-tree paper), best filling ratio is 0.4 for both
         * quadratic split and R*-tree split.
         */
        private static final double DEFAULT_FILLING_FACTOR = 0.4;
        private Optional<Integer> maxChildren = absent();
        private Optional<Integer> minChildren = absent();
        private Splitter splitter = new SplitterQuadratic();
        private Selector selector = new SelectorMinimalAreaIncrease();
        private boolean star = false;

        private Builder() {
        }

        /**
         * When the number of children in an R-tree node drops below this number
         * the node is deleted and the children are added on to the R-tree
         * again.
         * 
         * @param minChildren
         *            less than this number of children in a node triggers a
         *            redistribution of its children.
         * @return builder
         */
        public Builder minChildren(int minChildren) {
            this.minChildren = of(minChildren);
            return this;
        }

        /**
         * Sets the max number of children in an R-tree node.
         * 
         * @param maxChildren
         *            max number of children in R-tree node.
         * @return builder
         */
        public Builder maxChildren(int maxChildren) {
            this.maxChildren = of(maxChildren);
            return this;
        }

        /**
         * Sets the {@link Splitter} to use when maxChildren is reached.
         * 
         * @param splitter
         *            node splitting method to use
         * @return builder
         */
        public Builder splitter(Splitter splitter) {
            this.splitter = splitter;
            return this;
        }

        /**
         * Sets the node {@link Selector} which decides which branches to follow
         * when inserting or searching.
         * 
         * @param selector
         *            selects the branch to follow when inserting or searching
         * @return builder
         */
        public Builder selector(Selector selector) {
            this.selector = selector;
            return this;
        }

        /**
         * Sets the splitter to {@link SplitterRStar} and selector to
         * {@link SelectorRStar} and defaults to minChildren=10.
         * 
         * @return builder
         */
        public Builder star() {
            selector = new SelectorRStar();
            splitter = new SplitterRStar();
            star = true;
            return this;
        }

        /**
         * Builds the {@link RTree}.
         * 
         * @param <R>
         *            the entry type
         * @return RTree
         */
        public <R> RTree<R> create() {
            if (!maxChildren.isPresent())
                if (star)
                    maxChildren = of(MAX_CHILDREN_DEFAULT_STAR);
                else
                    maxChildren = of(MAX_CHILDREN_DEFAULT_GUTTMAN);
            if (!minChildren.isPresent())
                minChildren = of((int) Math.round(maxChildren.get() * DEFAULT_FILLING_FACTOR));
            return new RTree<R>(new Context(minChildren.get(), maxChildren.get(), selector,
                    splitter));
        }

    }

    /**
     * Adds an entry to the R-tree.
     * 
     * @param entry
     *            item to add to the R-tree.
     * @return a new immutable R-tree including the new entry
     */
    @SuppressWarnings("unchecked")
    public RTree<T> add(Entry<T> entry) {
        if (root.isPresent()) {
            List<Node<T>> nodes = root.get().add(entry);
            Node<T> node;
            if (nodes.size() == 1)
                node = nodes.get(0);
            else {
                node = new NonLeaf<T>(nodes, context);
            }
            return new RTree<T>(node, size + 1, context);
        } else
            return new RTree<T>(new Leaf<T>(Lists.newArrayList(entry), context), size + 1, context);
    }

    /**
     * Adds an entry comprised of the given value and Geometry.
     * 
     * @param value
     *            the value of the {@link Entry} to be added
     * @param geometry
     *            the geometry of the {@link Entry} to be added
     * @return a new immutable R-tree including the new entry
     */
    public RTree<T> add(T value, Geometry geometry) {
        return add(Entry.entry(value, geometry));
    }

    /**
     * Returns a new R-tree with the current entries and the additional entries
     * supplied as a parameter.
     * 
     * @param entries
     *            entries to add
     * @return R-tree with entries added
     */
    public RTree<T> add(Iterable<Entry<T>> entries) {
        RTree<T> tree = this;
        for (Entry<T> entry : entries)
            tree = tree.add(entry);
        return tree;
    }

    /**
     * Returns the Observable sequence of trees created by progressively adding
     * entries.
     * 
     * @param entries
     *            the entries to add
     * @return a sequence of trees
     */
    public Observable<RTree<T>> add(Observable<Entry<T>> entries) {
        return entries.scan(this, new Func2<RTree<T>, Entry<T>, RTree<T>>() {

            @Override
            public RTree<T> call(RTree<T> tree, Entry<T> entry) {
                return tree.add(entry);
            }
        });
    }

    /**
     * Returns the Observable sequence of trees created by progressively
     * deleting entries.
     * 
     * @param entries
     *            the entries to add
     * @param all
     *            if true delete all matching otherwise just first matching
     * @return a sequence of trees
     */
    public Observable<RTree<T>> delete(Observable<Entry<T>> entries, final boolean all) {
        return entries.scan(this, new Func2<RTree<T>, Entry<T>, RTree<T>>() {

            @Override
            public RTree<T> call(RTree<T> tree, Entry<T> entry) {
                return tree.delete(entry, all);
            }
        });
    }

    /**
     * Returns a new R-tree with the given entries deleted. If <code>all</code>
     * is false deletes only one if exists. If <code>all</code> is true deletes
     * all matching entries.
     * 
     * @param entries
     *            entries to delete
     * @param all
     *            if false deletes one if exists else deletes all
     * @return R-tree with entries deleted
     */
    public RTree<T> delete(Iterable<Entry<T>> entries, boolean all) {
        RTree<T> tree = this;
        for (Entry<T> entry : entries)
            tree = tree.delete(entry, all);
        return tree;
    }

    /**
     * If <code>all</code> is false deletes one entry matching the given value
     * and Geometry. If <code>all</code> is true deletes all entries matching
     * the given value and geometry. This method has no effect if the entry is
     * not present. The entry must match on both value and geometry to be
     * deleted.
     * 
     * @param value
     *            the value of the {@link Entry} to be deleted
     * @param geometry
     *            the geometry of the {@link Entry} to be deleted
     * @param all
     *            if false deletes one if exists else deletes all
     * @return a new immutable R-tree without one instance of the specified
     *         entry
     */
    public RTree<T> delete(T value, Geometry geometry, boolean all) {
        return delete(Entry.entry(value, geometry), all);
    }

    public RTree<T> delete(T value, Geometry geometry) {
        return delete(Entry.entry(value, geometry), false);
    }

    /**
     * Delete one entry if it exists. If multiple copies of the entry are in the
     * R-tree only one will be deleted. The entry must match on both value and
     * geometry to be deleted.
     * 
     * @param entry
     *            the {@link Entry} to be deleted
     * @param all
     *            if true deletes all matches otherwise deletes first found
     * @return a new immutable R-tree without one instance of the specified
     *         entry
     */
    public RTree<T> delete(Entry<T> entry, boolean all) {
        if (root.isPresent()) {
            NodeAndEntries<T> nodeAndEntries = root.get().delete(entry, all);
            if (nodeAndEntries.node().isPresent() && nodeAndEntries.node().get() == root.get())
                return this;
            else
                return new RTree<T>(nodeAndEntries.node(), size - nodeAndEntries.countDeleted()
                        - nodeAndEntries.entriesToAdd().size(), context).add(nodeAndEntries
                        .entriesToAdd());
        } else
            return this;
    }

    public RTree<T> delete(Entry<T> entry) {
        return delete(entry, false);
    }

    public RTree<T> delete(Iterable<Entry<T>> entries) {
        RTree<T> tree = this;
        for (Entry<T> entry : entries)
            tree = tree.delete(entry);
        return tree;
    }

    /**
     * <p>
     * Returns an Observable sequence of {@link Entry} that satisfy the given
     * condition. Note that this method is well-behaved only if:
     * </p>
     * 
     * <code>condition(g) is true for {@link Geometry} g implies condition(r) is true for the minimum bounding rectangles of the ancestor nodes</code>
     * 
     * <p>
     * <code>distance(g) &lt; sD</code> is an example of such a condition.
     * </p>
     * 
     * @param condition
     *            return Entries whose geometry satisfies the given condition
     * @return sequence of matching entries
     */
    @VisibleForTesting
    Observable<Entry<T>> search(Func1<? super Geometry, Boolean> condition) {
        if (root.isPresent())
            return Observable.create(new OnSubscribeSearch<T>(root.get(), condition));
        else
            return Observable.empty();
    }

    /**
     * Returns a predicate function that indicates if {@link Geometry}
     * intersects with a given rectangle.
     * 
     * @param r
     *            the rectangle to check intersection with
     * @return whether the geometry and the rectangle intersect
     */
    public static Func1<Geometry, Boolean> intersects(final Rectangle r) {
        return new Func1<Geometry, Boolean>() {
            @Override
            public Boolean call(Geometry g) {
                return g.intersects(r);
            }
        };
    }

    /**
     * Returns the always true predicate. See {@link RTree#entries()} for
     * example use.
     */
    private static final Func1<Geometry, Boolean> ALWAYS_TRUE = new Func1<Geometry, Boolean>() {
        @Override
        public Boolean call(Geometry rectangle) {
            return true;
        }
    };

    /**
     * Returns an {@link Observable} sequence of all {@link Entry}s in the
     * R-tree whose minimum bounding rectangle intersects with the given
     * rectangle.
     * 
     * @param r
     *            rectangle to check intersection with the entry mbr
     * @return entries that intersect with the rectangle r
     */
    public Observable<Entry<T>> search(final Rectangle r) {
        return search(intersects(r));
    }

    /**
     * Returns an {@link Observable} sequence of all {@link Entry}s in the
     * R-tree whose minimum bounding rectangle intersects with the given point.
     * 
     * @param p
     *            point to check intersection with the entry mbr
     * @return entries that intersect with the point p
     */
    public Observable<Entry<T>> search(final Point p) {
        return search(p.mbr());
    }

    /**
     * Returns an {@link Observable} sequence of all {@link Entry}s in the
     * R-tree whose minimum bounding rectangles are less than maxDistance from
     * the given rectangle.
     * 
     * @param r
     *            rectangle to measure distance from
     * @param maxDistance
     *            entries returned must be within this distance from rectangle r
     * @return the sequence of matching entries
     */
    public Observable<Entry<T>> search(final Rectangle r, final double maxDistance) {
        return search(new Func1<Geometry, Boolean>() {
            @Override
            public Boolean call(Geometry g) {
                return g.distance(r) < maxDistance;
            }
        });
    }

    /**
     * Returns an {@link Observable} sequence of all {@link Entry}s in the
     * R-tree whose minimum bounding rectangles are within maxDistance from the
     * given point.
     * 
     * @param p
     *            point to measure distance from
     * @param maxDistance
     *            entries returned must be within this distance from point p
     * @return the sequence of matching entries
     */
    public Observable<Entry<T>> search(final Point p, final double maxDistance) {
        return search(p.mbr(), maxDistance);
    }

    /**
     * Returns the nearest k entries (k=maxCount) to the given rectangle where
     * the entries are within a given maximum distance from the rectangle.
     * 
     * @param r
     *            rectangle
     * @param maxDistance
     *            max distance of returned entries from the rectangle
     * @param maxCount
     *            max number of entries to return
     * @return nearest entries to maxCount, not in any particular order
     */
    public Observable<Entry<T>> nearest(final Rectangle r, final double maxDistance, int maxCount) {
        return search(r, maxDistance).lift(
                new OperatorBoundedPriorityQueue<Entry<T>>(maxCount, Comparators
                        .<T> ascendingDistance(r)));
    }

    /**
     * Returns the nearest k entries (k=maxCount) to the given point where the
     * entries are within a given maximum distance from the point.
     * 
     * @param p
     *            point
     * @param maxDistance
     *            max distance of returned entries from the point
     * @param maxCount
     *            max number of entries to return
     * @return nearest entries to maxCount, not in any particular order
     */
    public Observable<Entry<T>> nearest(final Point p, final double maxDistance, int maxCount) {
        return nearest(p.mbr(), maxDistance, maxCount);
    }

    /**
     * Returns all entries in the tree as an {@link Observable} sequence.
     * 
     * @return all entries in the R-tree
     */
    public Observable<Entry<T>> entries() {
        return search(ALWAYS_TRUE);
    }

    /**
     * Returns a {@link Visualizer} for an image of given width and height and
     * restricted to the given view of the coordinates. The points in the view
     * are scaled to match the aspect ratio defined by the width and height.
     * 
     * @param width
     *            of the image in pixels
     * @param height
     *            of the image in pixels
     * @param view
     *            using the coordinate system of the entries
     * @return visualizer
     */
    public Visualizer visualize(int width, int height, Rectangle view) {
        return new Visualizer(this, width, height, view);
    }

    /**
     * Returns a {@link Visualizer} for an image of given width and height and
     * restricted to the the smallest view that fully contains the coordinates.
     * The points in the view are scaled to match the aspect ratio defined by
     * the width and height.
     * 
     * @param width
     *            of the image in pixels
     * @param height
     *            of the image in pixels
     * @return visualizer
     */
    public Visualizer visualize(int width, int height) {
        return visualize(width, height, calculateMaxView(this));
    }

    private Rectangle calculateMaxView(RTree<T> tree) {
        return tree
                .entries()
                .reduce(Optional.<Rectangle> absent(),
                        new Func2<Optional<Rectangle>, Entry<T>, Optional<Rectangle>>() {

                            @Override
                            public Optional<Rectangle> call(Optional<Rectangle> r, Entry<T> entry) {
                                if (r.isPresent())
                                    return of(r.get().add(entry.geometry().mbr()));
                                else
                                    return of(entry.geometry().mbr());
                            }
                        }).toBlocking().single().or(rectangle(0, 0, 0, 0));
    }

    Optional<? extends Node<T>> root() {
        return root;
    }

    /**
     * Returns true if and only if the R-tree is empty of entries.
     * 
     * @return is R-tree empty
     */
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * Returns the number of entries in the RTree.
     * 
     * @return the number of entries
     */
    public int size() {
        return size;
    }

    /**
     * Returns a {@link Context} containing the configuration of the RTree at
     * the time of instantiation.
     * 
     * @return the configuration of the RTree prior to instantiation
     */
    public Context context() {
        return context;
    }

    public String asString() {
        if (!root.isPresent())
            return "";
        else
            return asString(root.get(), "");
    }

    private String asString(Node<T> node, String margin) {
        final String marginIncrement = "  ";
        StringBuilder s = new StringBuilder();
        if (node instanceof NonLeaf) {
            s.append(margin);
            s.append("mbr=" + node.geometry());
            s.append('\n');
            NonLeaf<T> n = (NonLeaf<T>) node;
            for (Node<T> child : n.children()) {
                s.append(asString(child, margin + marginIncrement));
            }
        } else {
            Leaf<T> leaf = (Leaf<T>) node;
            s.append(margin);
            s.append("mbr=");
            s.append(leaf.geometry());
            s.append('\n');
            for (Entry<T> entry : leaf.entries()) {
                s.append(margin);
                s.append(marginIncrement);
                s.append("entry=");
                s.append(entry);
                s.append('\n');
            }
        }
        return s.toString();
    }

}
