/*
* Copyright (c) IBM Corporation 2017. All Rights Reserved.
* Project name: java-async-util
* This project is licensed under the Apache License 2.0, see LICENSE.
*/

package com.ibm.asyncutil.locks;

public class FairAsyncReadWriteLockTest
    extends AbstractAsyncReadWriteLockTest.AbstractAsyncReadWriteLockFairnessTest {
  @Override
  protected AsyncReadWriteLock getReadWriteLock() {
    return new FairAsyncReadWriteLock();
  }
}

