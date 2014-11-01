rtree
=========

In-memory immutable 2D [R-tree](http://en.wikipedia.org/wiki/R-tree) implementation in java using [RxJava Observables](https://github.com/ReactiveX/RxJava) for reactive processing of search results. 

Status: *released to Maven Central, ensure you use at least 0.4 to get fix for #7*

An [R-tree](http://en.wikipedia.org/wiki/R-tree) is a commonly used spatial index.

This was fun to make, has an elegant concise algorithm, is thread-safe and fast.

The algorithm to achieve immutability is cute. For insertion/deletion it involves recursion down to the 
required leaf node then recursion back up to replace the parent nodes up to the root. The guts of 
it is in [Leaf.java](src/main/java/com/github/davidmoten/rtree/Leaf.java) and [NonLeaf.java](src/main/java/com/github/davidmoten/rtree/NonLeaf.java).

[Backpressure](https://github.com/ReactiveX/RxJava/wiki/Backpressure) support required some complexity because effectively a
bookmark needed to be kept for a position in the tree and returned to later to continue traversal. An immutable stack containing
 the node and child index of the path nodes came to the rescue here and recursion was abandoned in favour of looping to prevent stack overflow (unfortunately java doesn't support tail recursion!).

Continuous integration with Travis: <a href="https://travis-ci.org/davidmoten/rtree"><img src="https://api.travis-ci.org/davidmoten/rtree.svg"/></a>

Maven site reports are [here](http://davidmoten.github.io/rtree/index.html) including [javadoc](http://davidmoten.github.io/rtree/apidocs/index.html).

Features
------------
* immutable R-tree suitable for concurrency
* Guttman's heuristics (Quadratic splitter) ([paper](https://www.google.com.au/url?sa=t&rct=j&q=&esrc=s&source=web&cd=1&cad=rja&uact=8&ved=0CB8QFjAA&url=http%3A%2F%2Fpostgis.org%2Fsupport%2Frtree.pdf&ei=ieEQVJuKGdK8uATpgoKQCg&usg=AFQjCNED9w2KjgiAa9UI-UO_0eWjcADTng&sig2=rZ_dzKHBHY62BlkBuw3oCw&bvm=bv.74894050,d.c2E))
* R*-tree heuristics ([paper](http://dbs.mathematik.uni-marburg.de/publications/myPapers/1990/BKSS90.pdf))
* Customizable [splitter](src/main/java/com/github/davidmoten/rtree/Splitter.java) and [selector](src/main/java/com/github/davidmoten/rtree/Selector.java)
* search returns [```Observable```](http://reactivex.io/RxJava/javadoc/rx/Observable.html) 
* search is cancelled by unsubscription
* search is ```O(log(n))``` on average
* insert, delete are ```O(n)``` worst case
* all search methods return lazy-evaluated streams offering efficiency and flexibility of functional style including functional composition and concurrency
* balanced delete
* uses structural sharing
* supports [backpressure](https://github.com/ReactiveX/RxJava/wiki/Backpressure)
* JMH benchmarks
* visualizer included
* R*-tree performs 425,000 searches/second returning 22 entries from a tree of 38,377 Greek earthquake locations on i7-920@2.67Ghz (maxChildren=4, minChildren=4). Insert at 135,000 entries per second.
* requires java 1.6 or later

Number of points = 1000, max children per node 8, Quadratic split: 

<img src="src/docs/quad-1000-8.png?raw=true"/>

Number of points = 1000, max children per node 8, R*-tree split. Notice that there is little overlap compared to the 
Quadratic split. This should provide better search performance (and in general benchmarks show this).

<img src="src/docs/star-1000-8.png?raw=true"/>

Getting started
----------------
Add this maven dependency to your pom.xml:

```xml
<dependency>
  <groupId>com.github.davidmoten</groupId>
  <artifactId>rtree</artifactId>
  <version>0.4</version>
</dependency>
```
###Instantiate an R-Tree
Use the static builder methods on the ```RTree``` class:

```java
// create an R-tree using Quadratic split with max
// children per node 4, min children 2 (the threshold
// at which members are redistributed)
RTree<String> tree = RTree.create();
```
You can specify a few parameters to the builder, including *minChildren*, *maxChildren*, *splitter*, *selector*:

```java
RTree<String> tree = RTree.minChildren(3).maxChildren(6).create();
```

###R*-tree
If you'd like an R*-tree (which uses a topological splitter on minimal margin, overlap area and area and a selector combination of minimal area increase, minimal overlap, and area):

```
RTree<String> tree = RTree.star().maxChildren(6).create();
```

See benchmarks below for some of the performance differences.

###Add items to the R-tree
When you add an item to the R-tree you need to provide a geometry that represents the 2D physical location or 
extension of the item. The ``Geometries`` builder provides these factory methods:

* ```Geometries.rectangle```
* ```Geometries.circle```
* ```Geometries.point```

To add an item to an R-tree:

```java
RTree<T> tree = RTree.create();
tree = tree.add(item, Geometries.point(10,20));
```
or 
```java
tree = tree.add(Enry.entry(item, Geometries.point(10,20));
```

###Remove an item in the R-tree
To remove an item from an R-tree, you need to match the item and its geometry:

```java
tree = tree.delete(item, Geometries.point(10,20));
```
or 
```java
tree = tree.delete(entry);
```

###Custom geometries
You can also write your own implementation of [```Geometry```](src/main/java/com/github/davidmoten/rtree/geometry/Geometry.java). An implementation of ```Geometry``` needs to specify methods to:

* measure distance to a rectangle (0 means they intersect)
* check intersection with a rectangle (you can reuse the distance method here if you want but it might affect performance)
* provide a minimum bounding rectangle
* implement ```equals``` and ```hashCode``` for consistent equality checking

For the R-tree to be well-behaved, the distance function needs to satisfy these properties:

* ```distance(r) >= 0 for all rectangles r```
* ```if rectangle r1 contains r2 then distance(r1)<=distance(r2)```
* ```distance(r) = 0 if and only if the geometry intersects the rectangle r``` 

###Searching
The advantage of an R-tree is the ability to search for items in a region reasonably quickly. 
On average search is ```O(log(n))``` but worst case is ```O(n)```.

Search methods return ```Observable``` sequences:
```java
Observable<Entry<T>> results = tree.search(Geometries.rectangle(0,0,2,2));
```
or search for items within a distance from the given geometry:
```java
Observable<Entry<T>> results = tree.search(Geometries.rectangle(0,0,2,2),5.0);
```
To return all entries from an R-tree:
```java
Observable<Entry<T>> results = tree.entries();
```

Example
--------------
```java
import com.github.davidmoten.rtree.RTree;
import static com.github.davidmoten.rtree.geometry.Geometries.*;

RTree<String> tree = RTree.maxChildren(5).create();
tree = tree.add("DAVE", point(10, 20))
           .add("FRED", point(12, 25))
           .add("MARY", point(97, 125));
 
Observable<Entry<String>> entries = tree.search(Rectangle.create(8, 15, 30, 35));
```

What do I do with the Observable thing?
-------------------------------------------
Very useful, see [RxJava](http://github.com/ReactiveX/RxJava).

As an example, suppose you want to filter the search results then apply a function on each and reduce to some best answer:

```java
import rx.Observable;
import rx.functions.*;
import rx.schedulers.Schedulers;

Func1<Entry<String>, Character> firstCharacter = entry -> entry.value().charAt(0);
Func2<Character,Character,Character> firstAlphabetically = (x,y) -> x <=y ? x : y;

Character result = 
    tree.search(Geometries.rectangle(8, 15, 30, 35))
        // filter for names alphabetically less than M
        .filter(entry -> entry.value() < "M")
        // get the first character of the name
        .map(entry -> firstCharacter(entry.value()))
        // reduce to the first character alphabetically 
        .reduce((x,y) -> firstAlphabetically(x,y))
        // subscribe to the stream and block for the result
        .toBlocking().single();
System.out.println(list);
```
output:
```
D
```

How to configure the R-tree for best performance
--------------------------------------------------
Check out the benchmarks below, but I recommend you do your own benchmarks because every data set will behave differently. If you don't want to benchmark then use the defaults. General rules based on the benchmarks:

* for data sets of <10,000 entries use the default R-tree (quadratic splitter with maxChildren=4)
* for data sets of >=10,000 entries use the star R-tree (R*-tree heuristics with maxChildren=4 by default)

Watch out though, the benchmark data sets had quite specific characteristics. The 1000 entry dataset was randomly generated (so is more or less uniformly distributed) and the *Greek* dataset was earthquake data with its own clustering characteristics. 


How do I just get an Iterable back from a search?
---------------------------------------------------------
If you are not familiar with the Observable API and want to skip the reactive stuff then here's how to get an ```Iterable``` from a search:

```java
Iterable<T> it = tree.search(Geometries.point(4,5)).toBlocking().toIterable();
```

Backpressure
-----------------
The backpressure slow path may be enabled by some RxJava operators. This may slow search performance by a factor of 3 but avoids possible out of memory errors and thread starvation due to asynchronous buffering. Backpressure is benchmarked below.

Visualizer
--------------
To visualize the R-tree in a PNG file of size 600 by 600 pixels just call:
```java
tree.visualize(600,600).save("target/mytree.png");
```
The result is like the images in the Features section above.

Visualize as text
--------------------
The ```RTree.asString()``` method returns output like this:

```
mbr=Rectangle [x1=10.0, y1=4.0, x2=62.0, y2=85.0]
  mbr=Rectangle [x1=28.0, y1=4.0, x2=34.0, y2=85.0]
    entry=Entry [value=2, geometry=Point [x=29.0, y=4.0]]
    entry=Entry [value=1, geometry=Point [x=28.0, y=19.0]]
    entry=Entry [value=4, geometry=Point [x=34.0, y=85.0]]
  mbr=Rectangle [x1=10.0, y1=45.0, x2=62.0, y2=63.0]
    entry=Entry [value=5, geometry=Point [x=62.0, y=45.0]]
    entry=Entry [value=3, geometry=Point [x=10.0, y=63.0]]
```

Dependencies
---------------------
This library has a dependency on *guava* 18.0 which is about 2.2M. If you are coding for Android you may want to use *ProGuard* to trim 
the final application size. The dependency is driven by extensive use of ```Optional```,```Preconditions```,```Objects```, and the use of
```MinMaxPriorityQueue``` for the *nearest-k* search. I'm open to the possibility of internalizing these dependencies if people care
about the dependency size a lot. Let me know.


How to build
----------------
```
git clone https://github.com/davidmoten/rtree.git
cd rtree
mvn clean install
```

How to run benchmarks
--------------------------
Benchmarks are provided by 
```
mvn clean install -Pbenchmark
```

### Notes
The *Greek* data referred to in the benchmarks is a collection of some 38,377 entries corresponding to the epicentres of earthquakes in Greece between 1964 and 2000. This data set is used by multiple studies on R-trees as a test case.

### Results

These were run on i7-920@2.67GHz.

```
Benchmark                                                                                  Mode  Samples       Score  Score error  Units
c.g.d.r.BenchmarksRTree.defaultRTreeInsertOneEntryInto1000EntriesMaxChildren004           thrpt       10  173095.547     4328.389  ops/s
c.g.d.r.BenchmarksRTree.defaultRTreeInsertOneEntryInto1000EntriesMaxChildren010           thrpt       10  209794.353     2039.506  ops/s
c.g.d.r.BenchmarksRTree.defaultRTreeInsertOneEntryInto1000EntriesMaxChildren032           thrpt       10   98481.649     2490.956  ops/s
c.g.d.r.BenchmarksRTree.defaultRTreeInsertOneEntryInto1000EntriesMaxChildren128           thrpt       10  274427.347     3815.611  ops/s
c.g.d.r.BenchmarksRTree.defaultRTreeInsertOneEntryIntoGreekDataEntriesMaxChildren004      thrpt       10  170821.549     1377.303  ops/s
c.g.d.r.BenchmarksRTree.defaultRTreeInsertOneEntryIntoGreekDataEntriesMaxChildren010      thrpt       10  211766.538     1704.674  ops/s
c.g.d.r.BenchmarksRTree.defaultRTreeInsertOneEntryIntoGreekDataEntriesMaxChildren032      thrpt       10  148804.038     1944.374  ops/s
c.g.d.r.BenchmarksRTree.defaultRTreeInsertOneEntryIntoGreekDataEntriesMaxChildren128      thrpt       10   86048.890     1522.720  ops/s
c.g.d.r.BenchmarksRTree.defaultRTreeSearchOf1000PointsMaxChildren004                      thrpt       10  670179.378     7843.764  ops/s
c.g.d.r.BenchmarksRTree.defaultRTreeSearchOf1000PointsMaxChildren010                      thrpt       10  259325.592     3963.649  ops/s
c.g.d.r.BenchmarksRTree.defaultRTreeSearchOf1000PointsMaxChildren032                      thrpt       10  310696.189     1508.965  ops/s
c.g.d.r.BenchmarksRTree.defaultRTreeSearchOf1000PointsMaxChildren128                      thrpt       10  410454.544     2281.689  ops/s
c.g.d.r.BenchmarksRTree.defaultRTreeSearchOfGreekDataPointsMaxChildren004                 thrpt       10  261197.965     5471.021  ops/s
c.g.d.r.BenchmarksRTree.defaultRTreeSearchOfGreekDataPointsMaxChildren010                 thrpt       10  161675.449    21285.502  ops/s
c.g.d.r.BenchmarksRTree.defaultRTreeSearchOfGreekDataPointsMaxChildren032                 thrpt       10  104811.974      593.740  ops/s
c.g.d.r.BenchmarksRTree.defaultRTreeSearchOfGreekDataPointsMaxChildren128                 thrpt       10   41552.337      533.886  ops/s
c.g.d.r.BenchmarksRTree.rStarTreeDeleteOneEveryOccurrenceFromGreekDataChildren010         thrpt       10  182263.817     3928.274  ops/s
c.g.d.r.BenchmarksRTree.rStarTreeInsertOneEntryInto1000EntriesMaxChildren004              thrpt       10  152468.490     2778.971  ops/s
c.g.d.r.BenchmarksRTree.rStarTreeInsertOneEntryInto1000EntriesMaxChildren010              thrpt       10  135756.911     1946.538  ops/s
c.g.d.r.BenchmarksRTree.rStarTreeInsertOneEntryInto1000EntriesMaxChildren032              thrpt       10   30282.783      402.369  ops/s
c.g.d.r.BenchmarksRTree.rStarTreeInsertOneEntryInto1000EntriesMaxChildren128              thrpt       10   98381.312     1604.900  ops/s
c.g.d.r.BenchmarksRTree.rStarTreeInsertOneEntryIntoGreekDataEntriesMaxChildren004         thrpt       10  134780.738     2252.492  ops/s
c.g.d.r.BenchmarksRTree.rStarTreeInsertOneEntryIntoGreekDataEntriesMaxChildren010         thrpt       10  108972.194     1690.232  ops/s
c.g.d.r.BenchmarksRTree.rStarTreeInsertOneEntryIntoGreekDataEntriesMaxChildren032         thrpt       10   26978.253      450.734  ops/s
c.g.d.r.BenchmarksRTree.rStarTreeInsertOneEntryIntoGreekDataEntriesMaxChildren128         thrpt       10    3641.561       33.981  ops/s
c.g.d.r.BenchmarksRTree.rStarTreeSearchOf1000PointsMaxChildren004                         thrpt       10  340900.549     6443.347  ops/s
c.g.d.r.BenchmarksRTree.rStarTreeSearchOf1000PointsMaxChildren010                         thrpt       10  549351.404    13284.330  ops/s
c.g.d.r.BenchmarksRTree.rStarTreeSearchOf1000PointsMaxChildren032                         thrpt       10  366802.165     6350.513  ops/s
c.g.d.r.BenchmarksRTree.rStarTreeSearchOf1000PointsMaxChildren128                         thrpt       10  570034.150     6170.146  ops/s
c.g.d.r.BenchmarksRTree.rStarTreeSearchOfGreekDataPointsMaxChildren004                    thrpt       10  424939.860     6696.086  ops/s
c.g.d.r.BenchmarksRTree.rStarTreeSearchOfGreekDataPointsMaxChildren010                    thrpt       10  291804.690     9954.872  ops/s
c.g.d.r.BenchmarksRTree.rStarTreeSearchOfGreekDataPointsMaxChildren010WithBackpressure    thrpt       10  112927.452     2068.581  ops/s
c.g.d.r.BenchmarksRTree.rStarTreeSearchOfGreekDataPointsMaxChildren032                    thrpt       10  279709.070     4622.236  ops/s
c.g.d.r.BenchmarksRTree.rStarTreeSearchOfGreekDataPointsMaxChildren128                    thrpt       10  208791.404     2751.306  ops/s
