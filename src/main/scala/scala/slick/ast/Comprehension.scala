package scala.slick.ast

import TypeUtil.typeToTypeUtil
import Util._

/** A SQL comprehension */
final case class Comprehension(from: Seq[(Symbol, Node)] = Seq.empty, where: Seq[Node] = Seq.empty, groupBy: Option[Node] = None, orderBy: Seq[(Node, Ordering)] = Seq.empty, select: Option[Node] = None, fetch: Option[Node] = None, offset: Option[Node] = None) extends DefNode {
  type Self = Comprehension
  val nodeChildren = from.map(_._2) ++ where ++ groupBy ++ orderBy.map(_._1) ++ select ++ fetch ++ offset
  override def nodeChildNames =
    from.map("from " + _._1) ++
    where.zipWithIndex.map("where" + _._2) ++
    groupBy.map(_ => "groupBy") ++
    orderBy.map("orderBy " + _._2) ++
    select.map(_ => "select") ++
    fetch.map(_ => "fetch") ++
    offset.map(_ => "offset")
  protected[this] def nodeRebuild(ch: IndexedSeq[Node]) = {
    val newFrom = ch.slice(0, from.length)
    val whereOffset = newFrom.length
    val newWhere = ch.slice(whereOffset, whereOffset + where.length)
    val groupByOffset = whereOffset + newWhere.length
    val newGroupBy = ch.slice(groupByOffset, groupByOffset + (if(groupBy.isDefined) 1 else 0))
    val orderByOffset = groupByOffset + newGroupBy.length
    val newOrderBy = ch.slice(orderByOffset, orderByOffset + orderBy.length)
    val selectOffset = orderByOffset + newOrderBy.length
    val newSelect = ch.slice(selectOffset, selectOffset + (if(select.isDefined) 1 else 0))
    val fetchOffset = selectOffset + newSelect.length
    val newFetch = ch.slice(fetchOffset, fetchOffset + (if(fetch.isDefined) 1 else 0))
    val offsetOffset = fetchOffset + newFetch.length
    val newOffset = ch.slice(offsetOffset, offsetOffset + (if(offset.isDefined) 1 else 0))
    copy(
      from = (from, newFrom).zipped.map { case ((s, _), n) => (s, n) },
      where = newWhere,
      groupBy = if(newGroupBy.isEmpty) None else Some(newGroupBy.head),
      orderBy = (orderBy, newOrderBy).zipped.map { case ((_, o), n) => (n, o) },
      select = if(newSelect.isEmpty) None else Some(newSelect.head),
      fetch = if(newFetch.isEmpty) None else Some(newFetch.head),
      offset = if(newOffset.isEmpty) None else Some(newOffset.head)
    )
  }
  def nodeGenerators = from
  override def getDumpInfo = super.getDumpInfo.copy(mainInfo = "")
  protected[this] def nodeRebuildWithGenerators(gen: IndexedSeq[Symbol]) =
    copy(from = (from, gen).zipped.map { case ((_, n), s) => (s, n) })
  def nodeWithComputedType2(scope: SymbolScope, typeChildren: Boolean, retype: Boolean): Self = {
    // Assign types to all "from" Nodes and compute the resulting scope
    val (genScope, f2) = from.foldLeft((scope, Vector.empty[Node])) { case ((sc, n2s), (s, n)) =>
      val n2 = n.nodeWithComputedType(sc, typeChildren, retype)
      val sc2 = sc + (s -> n2.nodeType.asCollectionType.elementType)
      (sc2, n2s :+ n2)
    }
    // Assign types to "where", "groupBy", "orderBy", "select", "fetch" and "offset" Nodes
    val w2 = mapOrNone(where)(_.nodeWithComputedType(genScope, typeChildren, retype))
    val g2 = mapOrNone(groupBy)(_.nodeWithComputedType(genScope, typeChildren, retype))
    val o = orderBy.map(_._1)
    val o2 = mapOrNone(o)(_.nodeWithComputedType(genScope, typeChildren, retype))
    val s2 = mapOrNone(select)(_.nodeWithComputedType(genScope, typeChildren, retype))
    val fetch2 = mapOrNone(fetch)(_.nodeWithComputedType(genScope, typeChildren, retype))
    val offset2 = mapOrNone(offset)(_.nodeWithComputedType(genScope, typeChildren, retype))
    // Check if the nodes changed
    val same = (from, f2).zipped.map(_._2 eq _).forall(identity) &&
      w2.isEmpty && g2.isEmpty && o2.isEmpty && s2.isEmpty && fetch2.isEmpty && offset2.isEmpty
    val newSel = s2.map(_.headOption).getOrElse(select)
    val newType =
      if(!nodeHasType || retype) {
        val el = newSel match {
          case Some(sel) => sel.nodeType.asCollectionType.elementType
          case None => f2.last.nodeType.asCollectionType.elementType
        }
        val tc = f2 match {
          case Vector(n, _*) => n.nodeType.asCollectionType.cons
          case _ => newSel.get.nodeType.asCollectionType.cons
        }
        CollectionType(tc, el)
      } else nodeType
    if(same && newType == nodeType) this else {
      // Compute result type
      copy(
        from = (from, f2).zipped.map { case ((s, _), n) => (s, n) },
        where = w2.getOrElse(where),
        groupBy = g2.map(_.headOption).getOrElse(groupBy),
        orderBy = o2.map(o2 => (orderBy, o2).zipped.map { case ((_, o), n) => (n, o) }).getOrElse(orderBy),
        select = s2.map(_.headOption).getOrElse(select),
        fetch = fetch2.map(_.headOption).getOrElse(fetch),
        offset = offset2.map(_.headOption).getOrElse(offset)
      ).nodeTyped(newType)
    }
  }
}

/** The row_number window function */
final case class RowNumber(by: Seq[(Node, Ordering)] = Seq.empty) extends TypedNode {
  type Self = RowNumber
  def tpe = ScalaBaseType.longType
  lazy val nodeChildren = by.map(_._1)
  protected[this] def nodeRebuild(ch: IndexedSeq[Node]) =
    copy(by = by.zip(ch).map{ case ((_, o), n) => (n, o) })
  override def nodeChildNames = by.zipWithIndex.map("by" + _._2)
  override def getDumpInfo = super.getDumpInfo.copy(mainInfo = "")
}
