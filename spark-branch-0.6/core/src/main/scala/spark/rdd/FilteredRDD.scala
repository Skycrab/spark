package spark.rdd

import spark.OneToOneDependency
import spark.RDD
import spark.Split

private[spark]
class FilteredRDD[T: ClassManifest](prev: RDD[T], f: T => Boolean) extends RDD[T](prev.context) {
  override def splits = prev.splits
  override val dependencies = List(new OneToOneDependency(prev))
  override val partitioner = prev.partitioner    // Since filter cannot change a partition's keys
  override def compute(split: Split) = prev.iterator(split).filter(f)
}
