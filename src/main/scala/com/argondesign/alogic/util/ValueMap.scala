////////////////////////////////////////////////////////////////////////////////
// Argon Design Ltd. Project P8009 Alogic
// Copyright (c) 2018 Argon Design Ltd. All rights reserved.
//
// This file is covered by the BSD (with attribution) license.
// See the LICENSE file for the precise wording of the license.
//
// Module: Alogic Compiler
// Author: Geza Lore
//
// DESCRIPTION:
//
// Trait providing the 'valueMap' word
//
// The 'valueMap' word can be used apply a closure to a single value
// e.g.: :
//  1 valueMap {
//    _ + 2
//  } valueMap {
//    _ * 3
//  }
// is the same as (1 + 2) * 3
////////////////////////////////////////////////////////////////////////////////

package com.argondesign.alogic.util

import scala.language.implicitConversions

// For importing with ValueMap._
object ValueMap {
  implicit final class ValueMapImpl[T](private val value: T) extends AnyVal {
    def valueMap[R](f: T => R): R = f(value)
  }
}

// For mixing into classes
trait ValueMap {
  import ValueMap.ValueMapImpl
  implicit final def any2ValueMapImpl[T](value: T): ValueMapImpl[T] = new ValueMapImpl(value)
}
