package com.typesafe.slick.testkit.tests

import com.typesafe.slick.testkit.util.{JdbcTestDB, AsyncTest}

class InsertTest extends AsyncTest[JdbcTestDB] {
  import tdb.profile.api._

  def testSimple = {
    class TestTable(tag: Tag, tname: String) extends Table[(Int, String)](tag, tname) {
      def id = column[Int]("id")
      def name = column[String]("name")
      def * = (id, name)
      def ins = (id, name)
    }

    val src1 = TableQuery(new TestTable(_, "src1_q"))
    val dst1 = TableQuery(new TestTable(_, "dst1_q"))
    val dst2 = TableQuery(new TestTable(_, "dst2_q"))
    val dst3 = TableQuery(new TestTable(_, "dst3_q"))

    val q2 = for(s <- src1 if s.id <= 2) yield s
    println("Insert 2: "+dst2.insertStatementFor(q2))
    val q3 = (42, "X".bind)
    println("Insert 3: "+dst2.insertStatementFor(q3))
    val q4comp = Compiled { dst2.filter(_.id < 10) }
    val dst3comp = Compiled { dst3 }

    Action.sequence(Seq(
      (src1.schema ++ dst1.schema ++ dst2.schema ++ dst3.schema).create,
      src1 += (1, "A"),
      src1.map(_.ins) ++= Seq((2, "B"), (3, "C")),
      dst1.insert(src1),
      dst1.to[Set].result.map(_ shouldBe Set((1,"A"), (2,"B"), (3,"C"))),
      dst2.insert(q2),
      dst2.to[Set].result.map(_ shouldBe Set((1,"A"), (2,"B"))),
      dst2.insertExpr(q3),
      dst2.to[Set].result.map(_ shouldBe Set((1,"A"), (2,"B"), (42,"X"))),
      dst3comp.insert(q4comp),
      dst3comp.result.map(v => v.to[Set] shouldBe Set((1,"A"), (2,"B")))
    ))
  }

  def testReturning = ifCap(jcap.returnInsertKey) {
    class A(tag: Tag) extends Table[(Int, String, String)](tag, "A") {
      def id = column[Int]("ID", O.PrimaryKey, O.AutoInc)
      def s1 = column[String]("S1")
      def s2 = column[String]("S2")
      def * = (id, s1, s2)
    }
    val as = TableQuery[A]
    def ins1 = as.map(a => (a.s1, a.s2)) returning as.map(_.id)
    def ins2 = as.map(a => (a.s1, a.s2)) returning as.map(a => (a.id, a.s1))
    def ins3 = as.map(a => (a.s1, a.s2)) returning as.map(_.id) into ((v, i) => (i, v._1, v._2))
    def ins4 = as.map(a => (a.s1, a.s2)) returning as.map(a => a)

    for {
      _ <- as.schema.create
      _ <- (ins1 += ("a", "b")) map { id1: Int => id1 shouldBe 1 }
      _ <- ifCap(jcap.returnInsertOther) {
        (ins2 += ("c", "d")) map { id2: (Int, String) => id2 shouldBe (2, "c") }
      }
      _ <- ifNotCap(jcap.returnInsertOther) {
        (ins1 += ("c", "d")) map { id2: Int => id2 shouldBe 2 }
      }
      _ <- (ins1 ++= Seq(("e", "f"), ("g", "h"))) map (_ shouldBe Seq(3, 4))
      _ <- (ins3 += ("i", "j")) map (_ shouldBe (5, "i", "j"))
      _ <- ifCap(jcap.returnInsertOther) {
        (ins4 += ("k", "l")) map { id5: (Int, String, String) => id5 shouldBe (6, "k", "l") }
      }
    } yield ()
  }

  def testForced = {
    class T(tag: Tag) extends Table[(Int, String)](tag, "t_forced") {
      def id = column[Int]("id", O.AutoInc, O.PrimaryKey)
      def name = column[String]("name")
      def * = (id, name)
      def ins = (id, name)
    }
    val ts = TableQuery[T]

    seq(
      ts.schema.create,
      ts += (101, "A"),
      ts.map(_.ins) ++= Seq((102, "B"), (103, "C")),
      ts.filter(_.id > 100).length.result.map(_ shouldBe 0),
      ifCap(jcap.forceInsert)(seq(
        ts.forceInsert(104, "A"),
        ts.map(_.ins).forceInsertAll(Seq((105, "B"), (106, "C"))),
        ts.filter(_.id > 100).length.result.map(_ shouldBe 3),
        ts.map(_.ins).forceInsertAll(Seq((111, "D"))),
        ts.filter(_.id > 100).length.result.map(_ shouldBe 4)
      ))
    )
  }

  def testInsertOrUpdatePlain = {
    class T(tag: Tag) extends Table[(Int, String)](tag, "t_merge") {
      def id = column[Int]("id", O.PrimaryKey)
      def name = column[String]("name")
      def * = (id, name)
      def ins = (id, name)
    }
    val ts = TableQuery[T]

    for {
      _ <- ts.schema.create
      _ <- ts ++= Seq((1, "a"), (2, "b"))
      _ <- ts.insertOrUpdate((3, "c")).map(_ shouldBe 1)
      _ <- ts.insertOrUpdate((1, "d")).map(_ shouldBe 1)
      _ <- ts.sortBy(_.id).result.map(_ shouldBe Seq((1, "d"), (2, "b"), (3, "c")))
    } yield ()
  }

  def testInsertOrUpdateAutoInc = {
    class T(tag: Tag) extends Table[(Int, String)](tag, "T_MERGE2") {
      def id = column[Int]("ID", O.AutoInc, O.PrimaryKey)
      def name = column[String]("NAME")
      def * = (id, name)
      def ins = (id, name)
    }
    val ts = TableQuery[T]

    for {
      _ <- ts.schema.create
      _ <- ts ++= Seq((1, "a"), (2, "b"))
      _ <- ts.insertOrUpdate((0, "c")).map(_ shouldBe 1)
      _ <- ts.insertOrUpdate((1, "d")).map(_ shouldBe 1)
      _ <- ts.sortBy(_.id).result.map(_ shouldBe Seq((1, "d"), (2, "b"), (3, "c")))
      _ <- ifCap(jcap.returnInsertKey) {
        val q = ts returning ts.map(_.id)
        for {
          _ <- q.insertOrUpdate((0, "e")).map(_ shouldBe Some(4))
          _ <- q.insertOrUpdate((1, "f")).map(_ shouldBe None)
          _ <- ts.sortBy(_.id).result.map(_ shouldBe Seq((1, "f"), (2, "b"), (3, "c"), (4, "e")))
        } yield ()
      }
    } yield ()
  }
}
