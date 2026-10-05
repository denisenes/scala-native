import scala.scalanative.unsafe.*
import kernel.{GC, System}

final case class Node(left: Node, right: Node)

object GCBench:
  private inline val DefaultIterations = 3
  private inline val TreeDepth = 12
  private inline val GarbageTreeDepth = 6
  private inline val GarbageWindow = 32
  private inline val MinCollections = 5
  private inline val MaxGarbageRounds = 10000000L

  private def buildTree(depth: Int): Node =
    if depth == 0 then Node(null, null)
    else Node(buildTree(depth - 1), buildTree(depth - 1))

  private def countNodes(node: Node): Int =
    if node == null then 0
    else 1 + countNodes(node.left) + countNodes(node.right)

  private def treeHeight(node: Node): Int =
    if node == null then -1
    else
      val left = treeHeight(node.left)
      val right = treeHeight(node.right)
      (if left > right then left else right) + 1

  /** Allocates short-lived trees through a sliding window until at least
   *  `minimum` garbage collections have run; returns the number of rounds.
   */
  private def churnGarbage(minimum: Long): Long =
    val window = new Array[Node](GarbageWindow)
    val target = GC.gcStatsCollectionTotal() + minimum
    var rounds = 0L
    while GC.gcStatsCollectionTotal() < target && rounds < MaxGarbageRounds do
      window((rounds % GarbageWindow).toInt) = buildTree(GarbageTreeDepth)
      rounds += 1
    rounds

  private def runIteration(): Boolean =
    val expectedNodes = (1 << (TreeDepth + 1)) - 1
    val root = buildTree(TreeDepth)

    val collectionsBefore = GC.gcStatsCollectionTotal()
    val rounds = churnGarbage(MinCollections)
    val collections = GC.gcStatsCollectionTotal() - collectionsBefore

    val nodes = countNodes(root)
    val height = treeHeight(root)
    val valid = nodes == expectedNodes && height == TreeDepth

    System.print(c"tree: ")
    System.print(nodes)
    System.print(c" nodes (expected ")
    System.print(expectedNodes)
    System.print(c"), height ")
    System.print(height)
    System.print(c", GCs: ")
    System.print(collections)
    System.print(c", garbage rounds: ")
    System.print(rounds)
    System.print(c" -> ")
    valid

  def run(iterations: Int = DefaultIterations): Unit =
    System.println(c"[bench] GC benchmark start")
    var iteration = 0
    var passed = true
    while iteration < iterations do
      System.print(c"[bench] iteration ")
      System.print(iteration + 1)
      System.print(c"/")
      System.print(iterations)
      System.print(c": ")
      if runIteration() then System.println(c"ok")
      else
        System.println(c"FAILED")
        passed = false
      iteration += 1
    if passed then System.println(c"[bench] all iterations passed")
    else System.println(c"[bench] FAILED")
