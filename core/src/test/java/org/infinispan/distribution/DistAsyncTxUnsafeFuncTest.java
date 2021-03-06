package org.infinispan.distribution;

import org.testng.annotations.Test;

@Test(groups = "functional", testName = "distribution.DistAsyncTxUnsafeFuncTest")
public class DistAsyncTxUnsafeFuncTest extends DistAsyncTxFuncTest {
   public DistAsyncTxUnsafeFuncTest() {
      testRetVals = false;
      cleanup = CleanupPhase.AFTER_METHOD; // ensure any stale TXs are wiped
   }
}
