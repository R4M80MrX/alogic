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
// Thrown when unreachable code is well, reached
////////////////////////////////////////////////////////////////////////////////

package com.argondesign.alogic.util

case class UnreachableException()
    extends Exception("The hovercraft is full of eels! Please file a bug report")
