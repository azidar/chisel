/*
 Copyright (c) 2011, 2012, 2013 The Regents of the University of
 California (Regents). All Rights Reserved.  Redistribution and use in
 source and binary forms, with or without modification, are permitted
 provided that the following conditions are met:

    * Redistributions of source code must retain the above
      copyright notice, this list of conditions and the following
      two paragraphs of disclaimer.
    * Redistributions in binary form must reproduce the above
      copyright notice, this list of conditions and the following
      two paragraphs of disclaimer in the documentation and/or other materials
      provided with the distribution.
    * Neither the name of the Regents nor the names of its contributors
      may be used to endorse or promote products derived from this
      software without specific prior written permission.

 IN NO EVENT SHALL REGENTS BE LIABLE TO ANY PARTY FOR DIRECT, INDIRECT,
 SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, INCLUDING LOST PROFITS,
 ARISING OUT OF THE USE OF THIS SOFTWARE AND ITS DOCUMENTATION, EVEN IF
 REGENTS HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

 REGENTS SPECIFICALLY DISCLAIMS ANY WARRANTIES, INCLUDING, BUT NOT
 LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 A PARTICULAR PURPOSE. THE SOFTWARE AND ACCOMPANYING DOCUMENTATION, IF
 ANY, PROVIDED HEREUNDER IS PROVIDED "AS IS". REGENTS HAS NO OBLIGATION
 TO PROVIDE MAINTENANCE, SUPPORT, UPDATES, ENHANCEMENTS, OR
 MODIFICATIONS.
*/

package Chisel

import ChiselError._
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.HashMap
import scala.collection.mutable.BufferProxy
import scala.collection.mutable.Stack
import scala.math._
import Vec._
import Node._

object VecMux {
  def apply(addr: UInt, elts: Seq[Data]): Data = {
    def doit(elts: Seq[Data], pos: Int): Data = {
      if (elts.length == 1) {
        elts(0)
      } else {
        val newElts = (0 until elts.length/2).map(i => Mux(addr(pos), elts(2*i + 1), elts(2*i)))
        doit(newElts ++ elts.slice(elts.length/2*2, elts.length), pos + 1)
      }
    }
    doit(elts, 0)
  }
}

object Vec {

  /** Returns a new *Vec* from a sequence of *Data* nodes.
    */
  def apply[T <: Data](elts: Iterable[T]): Vec[T] = {
    val res =
      if (!elts.isEmpty && elts.forall(_.isLit)) ROM(elts)
      else new Vec[T](i => elts.head.clone)
    res.self ++= elts
    res
  }

  /** Returns a new *Vec* from the concatenation of a *Data* node
    and a sequence of *Data* nodes.
    */
  def apply[T <: Data](elt0: T, elts: T*): Vec[T] =
    apply(elt0 +: elts.toSeq)

  /** Returns an array that contains the results of some element computation
    a number of times.

    Note that this means that elem is computed a total of n times.
    */
  def fill[T <: Data](n: Int)(gen: => T): Vec[T] = {
    Vec.tabulate(n){ i => gen }
  }

  /** Returns an array containing values of a given function over
    a range of integer values starting from 0.
    */
  def tabulate[T <: Data](n: Int)(gen: (Int) => T): Vec[T] =
    apply((0 until n).map(i => gen(i)))

  def tabulate[T <: Data](n1: Int, n2: Int)(f: (Int, Int) => T): Vec[Vec[T]] =
    tabulate(n1)(i1 => tabulate(n2)(f(i1, _)))

}

class VecProc(enables: Iterable[Bool], elms: Iterable[Data]) extends proc {
  override def procAssign(src: Node): Unit = {
    for ((en, elm) <- enables zip elms) when (en) {
      if(elm.comp != null) elm.comp procAssign src
      else elm.asInstanceOf[Bits] procAssign src
    }
  }
}

class Vec[T <: Data](val gen: (Int) => T) extends Aggregate with VecLike[T] with Cloneable {
  val self = new ArrayBuffer[T]
  val readPortCache = new HashMap[UInt, T]
  var sortedElementsCache: ArrayBuffer[ArrayBuffer[Data]] = null

  override def apply(idx: Int): T = self(idx)

  def sortedElements: ArrayBuffer[ArrayBuffer[Data]] = {
    if (sortedElementsCache == null) {
      sortedElementsCache = new ArrayBuffer[ArrayBuffer[Data]]

      // create buckets for each elm in data type
      for(i <- 0 until this(0).flatten.length)
        sortedElementsCache += new ArrayBuffer[Data]

      // fill out buckets
      for(elm <- this) {
        for(((n, io), i) <- elm.flatten zip elm.flatten.indices) {
          //val bits = io.toBits
          //bits.comp = io.comp
          sortedElementsCache(i) += io.asInstanceOf[Data]
        }
      }
    }
    sortedElementsCache
  }

  def apply(ind: UInt): T =
    read(ind)

  def write(addr: UInt, data: T): Unit =
    this(addr) := data

  def read(addr: UInt): T = {
    if(readPortCache.contains(addr)) {
      return readPortCache(addr)
    }

    val iaddr = UInt(width = log2Up(length))
    iaddr assign addr
    val enables = (UInt(1) << iaddr).toBools
    val res = this(0).clone
    for(((n, io), sortedElm) <- res.flatten zip sortedElements) {
      io assign VecMux(iaddr, sortedElm)

      // setup the comp for writes
      val io_comp = new VecProc(enables, sortedElm)
      io.comp = io_comp
    }
    readPortCache += (addr -> res)
    res.setIsTypeNode
    res
  }

  override def flatten: Array[(String, Bits)] = {
    val res = new ArrayBuffer[(String, Bits)]
    for (elm <- self.reverse)
      res ++= elm.flatten
    res.toArray
  }

  override def <>(src: Node) {
    src match {
      case other: Vec[T] => {
        for((b, o) <- self zip other.self)
          b <> o
      }
    }
  }

  def <>(src: Vec[T]) {
    for((b, e) <- self zip src)
      b <> e;
  }

  def <>(src: Iterable[T]) {
    for((b, e) <- self zip src)
      b <> e;
  }

  override protected def colonEquals[T <: Data](that: Iterable[T]): Unit = {
    if (comp != null) {
      comp procAssign Vec(that)
    } else {
      def unidirectional[U <: Data](who: Iterable[(String, Bits)]) =
        who.forall(_._2.dir == who.head._2.dir)

      assert(this.size == that.size, {
        ChiselError.error("Can't wire together Vecs of mismatched lengths")
      })

      assert(unidirectional(this.flatten), {
        ChiselError.error("Cannot mix directions on left hand side of :=")
      })

      assert(unidirectional(that.flatMap(_.flatten)), {
        ChiselError.error("Cannot mix directions on left hand side of :=")
      })

      for ((me, other) <- this zip that)
        me := other
    }
  }

  override protected def colonEquals(that: Bits): Unit = {
    for (i <- 0 until length)
      this(i) := that(i)
  }

  // We need this special := because Iterable[T] is not a Data.
  def :=[T <: Data](that: Iterable[T]): Unit = colonEquals(that)

  override def removeTypeNodes() {
    for(bundle <- self)
      bundle.removeTypeNodes
  }

  override def flip(): this.type = {
    for(b <- self)
      b.flip();
    this
  }

  override def nameIt (path: String, isNamingIo: Boolean) {
    if( !named
      && (name.isEmpty
        || (!path.isEmpty && name != path)) ) {
      val prevPrefix = if (name.length > 0) name + "_" else ""
      name = path
      val prefix = if (name.length > 0) name + "_" else ""
      for( (elm, i) <- self.zipWithIndex ) {
        val prevElmPrefix = prevPrefix + i
        val suffix = if( elm.name.startsWith(prevElmPrefix) ) {
          /* XXX Cludgy! We remove the previous prefix and regenerate
          the _elm_ name with a new prefix. */
          elm.name.substring(prevElmPrefix.length)
        } else {
          elm.name
        }
        elm.nameIt(prefix + i + suffix, isNamingIo)
      }
    } else {
      /* We are trying to rename a Vec that has a fixed name. */
    }
  }

  override def clone(): this.type =
    Vec.tabulate(size)(gen).asInstanceOf[this.type]
    //Vec(this: Seq[T]).asInstanceOf[this.type]

  override def asDirectionless(): this.type = {
    self.foreach(_.asDirectionless)
    this
  }

  override def asOutput(): this.type = {
    self.foreach(_.asOutput)
    this
  }

  override def asInput(): this.type = {
    self.foreach(_.asInput)
    this
  }

  override def setIsTypeNode() {
    isTypeNode = true;
    for(elm <- self)
      elm.setIsTypeNode
  }

  def length: Int = self.size

  override val hashCode: Int = _id
  override def equals(that: Any): Boolean = this eq that.asInstanceOf[AnyRef]
  
  // Don't return 0 for getwidth - #247
  // Return the sum of our constituent widths.
  override def getWidth(): Int = self.map(_.getWidth).foldLeft(0)(_ + _)

}

trait VecLike[T <: Data] extends collection.IndexedSeq[T] {
  def read(idx: UInt): T
  def write(idx: UInt, data: T): Unit
  def apply(idx: UInt): T

  def forall(p: T => Bool): Bool = (this map p).fold(Bool(true))(_&&_)
  def exists(p: T => Bool): Bool = (this map p).fold(Bool(false))(_||_)
  def contains[T <: Bits](x: T): Bool = this.exists(_ === x)
  def count(p: T => Bool): UInt = PopCount((this map p).toSeq)

  private def indexWhereHelper(p: T => Bool) = this map p zip (0 until length).map(i => UInt(i))
  def indexWhere(p: T => Bool): UInt = PriorityMux(indexWhereHelper(p))
  def lastIndexWhere(p: T => Bool): UInt = PriorityMux(indexWhereHelper(p).reverse)
  def onlyIndexWhere(p: T => Bool): UInt = Mux1H(indexWhereHelper(p))
}
