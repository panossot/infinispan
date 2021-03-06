package org.infinispan.functional;

import java.io.Serializable;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.infinispan.Cache;
import org.infinispan.commons.api.functional.EntryView.ReadEntryView;
import org.infinispan.commons.api.functional.EntryView.ReadWriteEntryView;
import org.infinispan.commons.api.functional.EntryView.WriteEntryView;
import org.infinispan.commons.api.functional.FunctionalMap.ReadWriteMap;
import org.infinispan.commons.api.functional.FunctionalMap.WriteOnlyMap;
import org.infinispan.commons.api.functional.Param;
import org.infinispan.functional.impl.ReadWriteMapImpl;
import org.infinispan.functional.impl.WriteOnlyMapImpl;
import org.infinispan.remoting.transport.Address;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

/**
 * @author Radim Vansa &lt;rvansa@redhat.com&gt; && Krzysztof Sobolewski &lt;Krzysztof.Sobolewski@atende.pl&gt;
 */
@Test(groups = "functional", testName = "functional.AbstractFunctionalOpTest")
public abstract class AbstractFunctionalOpTest extends AbstractFunctionalTest {

   // As the functional API should not have side effects, it's hard to verify its execution when it does not
   // have any return value.
   static AtomicInteger invocationCount = new AtomicInteger();
   WriteOnlyMap<Object, String> wo;
   ReadWriteMap<Object, String> rw;
   WriteOnlyMap<Integer, String> lwo;
   ReadWriteMap<Integer, String> lrw;

   public AbstractFunctionalOpTest() {
      numNodes = 4;
      numDistOwners = 2;
      cleanup = CleanupPhase.AFTER_METHOD;
   }

   protected static void assertInvocations(int count) {
      assertEquals(invocationCount.get(), count);
   }

   @DataProvider(name = "methods")
   public static Object[][] methods() {
      return Stream.of(Method.values()).map(m -> new Object[]{m}).toArray(Object[][]::new);
   }

   @DataProvider(name = "owningModeAndMethod")
   public static Object[][] owningModeAndMethod() {
      return Stream.of(Boolean.TRUE, Boolean.FALSE)
            .flatMap(isSourceOwner -> Stream.of(Method.values())
                  .map(method -> new Object[]{isSourceOwner, method}))
            .toArray(Object[][]::new);
   }

   @DataProvider(name = "owningModeAndReadWrites")
   public static Object[][] owningModeAndReadWrites() {
      return Stream.of(Boolean.TRUE, Boolean.FALSE)
            .flatMap(isSourceOwner -> Stream.of(Method.values()).filter(m -> m.doesRead)
                  .map(method -> new Object[]{isSourceOwner, method}))
            .toArray(Object[][]::new);
   }

   static Address getAddress(Cache<Object, Object> cache) {
      return cache.getAdvancedCache().getRpcManager().getAddress();
   }

   @BeforeMethod
   public void resetInvocationCount() {
      invocationCount.set(0);
   }

   @Override
   @BeforeMethod
   public void createBeforeMethod() throws Throwable {
      super.createBeforeMethod();
      this.wo = WriteOnlyMapImpl.create(fmapD1).withParams(Param.FutureMode.COMPLETED);
      this.rw = ReadWriteMapImpl.create(fmapD1).withParams(Param.FutureMode.COMPLETED);
      this.lwo = WriteOnlyMapImpl.create(fmapL1).withParams(Param.FutureMode.COMPLETED);
      this.lrw = ReadWriteMapImpl.create(fmapL1).withParams(Param.FutureMode.COMPLETED);
   }

   protected Object getKey(boolean isSourceOwner) {
      Object key;
      if (isSourceOwner) {
         // this is simple: find a key that is local to the originating node
         key = getKeyForCache(0, DIST);
      } else {
         // this is more complicated: we need a key that is *not* local to the originating node
         key = IntStream.iterate(0, i -> i + 1)
               .mapToObj(i -> "key" + i)
               .filter(k -> !cache(0, DIST).getAdvancedCache().getDistributionManager().getLocality(k).isLocal())
               .findAny()
               .get();
      }
      return key;
   }

   enum Method {
      WO_EVAL(false, (key, wo, rw, read, write) ->
            wo.eval(key, (Consumer<WriteEntryView<String>> & Serializable) view -> {
               invocationCount.incrementAndGet();
               write.accept(view);
            }).join()),
      WO_EVAL_VALUE(false, (key, wo, rw, read, write) ->
            wo.eval(key, null, (BiConsumer<String, WriteEntryView<String>> & Serializable)
                  (v, view) -> {
                     invocationCount.incrementAndGet();
                     write.accept(view);
                  }).join()),
      WO_EVAL_MANY(false, (key, wo, rw, read, write) ->
            wo.evalMany(Collections.singleton(key), (Consumer<WriteEntryView<String>> & Serializable) view -> {
               invocationCount.incrementAndGet();
               write.accept(view);
            }).join()),
      WO_EVAL_MANY_ENTRIES(false, (key, wo, rw, read, write) ->
            wo.evalMany(Collections.singletonMap(key, null),
                  (BiConsumer<String, WriteEntryView<String>> & Serializable) (v, view) -> {
                     invocationCount.incrementAndGet();
                     write.accept(view);
                  }).join()),
      RW_EVAL(true, (key, wo, rw, read, write) ->
            rw.eval(key,
                  (Function<ReadWriteEntryView<Object, String>, Object> & Serializable) view -> {
                     invocationCount.incrementAndGet();
                     read.accept(view);
                     write.accept(view);
                     return null;
                  }).join()),
      RW_EVAL_VALUE(true, (key, wo, rw, read, write) ->
            rw.eval(key, null,
                  (BiFunction<String, ReadWriteEntryView<Object, String>, Object> & Serializable) (v, view) -> {
                     invocationCount.incrementAndGet();
                     read.accept(view);
                     write.accept(view);
                     return null;
                  }).join()),
      RW_EVAL_MANY(true, (key, wo, rw, read, write) ->
            rw.evalMany(Collections.singleton(key),
                  (Function<ReadWriteEntryView<Object, String>, Object> & Serializable) view -> {
                     invocationCount.incrementAndGet();
                     read.accept(view);
                     write.accept(view);
                     return null;
                  }).forEach(v -> {
            })),
      RW_EVAL_MANY_ENTRIES(true, (key, wo, rw, read, write) ->
            rw.evalMany(Collections.singletonMap(key, null),
                  (BiFunction<String, ReadWriteEntryView<Object, String>, Object> & Serializable) (v, view) -> {
                     invocationCount.incrementAndGet();
                     read.accept(view);
                     write.accept(view);
                     return null;
                  }).forEach(v -> {
            })),;

      final Performer action;
      final boolean doesRead;

      Method(boolean doesRead, Performer action) {
         this.doesRead = doesRead;
         this.action = action;
      }

      @FunctionalInterface
      interface Performer<K> {
         void eval(K key,
                   WriteOnlyMap<K, String> wo, ReadWriteMap<K, String> rw,
                   Consumer<ReadEntryView<K, String>> read, Consumer<WriteEntryView<String>> write);
      }
   }
}
