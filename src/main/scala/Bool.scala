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

object Bool {
  def apply(x: Boolean): Bool = Lit(if(x) 1 else 0, 1){Bool()}

  def apply(dir: IODirection = null): Bool = {
    val res = new Bool();
    res.dir = dir;
    res.init("", 1)
    res
  }

  /** Factory method to create a don't-care. */
  def DC = Lit("b?", 1){Bool()}
}

class Bool extends UInt {
  protected[Chisel] var canBeUsedAsDefault: Boolean = false

  /** Factory method to create and assign a *Bool* type to a Node *n*.
    */
  override def fromNode(n: Node): this.type = {
    Bool(OUTPUT).asTypeFor(n).asInstanceOf[this.type]
  }

  override def fromInt(x: Int): this.type = {
    Bool(x > 0).asInstanceOf[this.type]
  }

  override protected def colonEquals(src: Bits): Unit = src match {
    case _: Bool => super.colonEquals(src(0))
    case _ => {
      val gotWidth = src.getWidth()
      if (gotWidth < 1) {
        throw new Exception("unable to automatically convert " + src + " to Bool, convert manually instead");
      } else if (gotWidth > 1) {
        throw new Exception("multi bit signal " + src + " converted to Bool");
      }
      super.colonEquals(src(0)) // We only have one bit in *src*.
    }
  }

  def && (b: Bool): Bool =
    if (this.isLit) { if (isTrue) b else Bool(false) }
    else if (b.isLit) b && this
    else if (this._isComplementOf(b)) Bool(false)
    else newBinaryOp(b, "&")

  def || (b: Bool): Bool =
    if (this.isLit) { if (isTrue) Bool(true) else b }
    else if (b.isLit) b || this
    else if (this._isComplementOf(b)) Bool(true)
    else newBinaryOp(b, "|")

  override def unary_!(): Bool = this ^ Bool(true)

  def isTrue: Boolean = litValue() == 1
}
