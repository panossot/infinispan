package org.infinispan.interceptors.compat;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Spliterator;
import java.util.stream.StreamSupport;

import org.infinispan.Cache;
import org.infinispan.CacheSet;
import org.infinispan.CacheStream;
import org.infinispan.cache.impl.Caches;
import org.infinispan.commands.MetadataAwareCommand;
import org.infinispan.commands.read.EntrySetCommand;
import org.infinispan.commands.read.GetAllCommand;
import org.infinispan.commands.read.GetCacheEntryCommand;
import org.infinispan.commands.read.GetKeyValueCommand;
import org.infinispan.commands.read.KeySetCommand;
import org.infinispan.commands.write.PutKeyValueCommand;
import org.infinispan.commands.write.PutMapCommand;
import org.infinispan.commands.write.RemoveCommand;
import org.infinispan.commands.write.ReplaceCommand;
import org.infinispan.commons.util.CloseableIterator;
import org.infinispan.commons.util.CloseableIteratorMapper;
import org.infinispan.commons.util.CloseableSpliterator;
import org.infinispan.compat.TypeConverter;
import org.infinispan.container.InternalEntryFactory;
import org.infinispan.container.entries.CacheEntry;
import org.infinispan.container.versioning.VersionGenerator;
import org.infinispan.context.Flag;
import org.infinispan.context.InvocationContext;
import org.infinispan.distribution.DistributionManager;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.interceptors.BasicInvocationStage;
import org.infinispan.interceptors.DDAsyncInterceptor;
import org.infinispan.metadata.Metadata;
import org.infinispan.stream.impl.interceptor.AbstractDelegatingEntryCacheSet;
import org.infinispan.stream.impl.interceptor.AbstractDelegatingKeyCacheSet;
import org.infinispan.stream.impl.local.EntryStreamSupplier;
import org.infinispan.stream.impl.local.KeyStreamSupplier;
import org.infinispan.stream.impl.local.LocalCacheStream;
import org.infinispan.stream.impl.spliterators.IteratorAsSpliterator;

/**
 * Base implementation for an interceptor that applies type conversion to the data stored in the cache. Subclasses need
 * to provide a suitable TypeConverter.
 *
 * @author Galder Zamarreño
 */
public abstract class BaseTypeConverterInterceptor<K, V> extends DDAsyncInterceptor {

   private InternalEntryFactory entryFactory;
   private VersionGenerator versionGenerator;
   private Cache<K, V> cache;


   @Inject
   protected void init(InternalEntryFactory entryFactory, VersionGenerator versionGenerator, Cache<K, V> cache) {
      this.entryFactory = entryFactory;
      this.versionGenerator = versionGenerator;
      this.cache = cache;
   }

   /**
    * Subclasses need to return a TypeConverter instance that is appropriate for a cache operation with the specified flags.
    *
    * @param flags the set of flags for the current cache operation
    * @return the converter, never {@code null}
    */
   protected abstract TypeConverter<Object, Object, Object, Object> determineTypeConverter(Set<Flag> flags);

   @Override
   public BasicInvocationStage visitPutKeyValueCommand(InvocationContext ctx, PutKeyValueCommand command) throws Throwable {
      if (!ctx.isOriginLocal()) {
         return super.visitPutKeyValueCommand(ctx, command);
      }
      Object key = command.getKey();
      TypeConverter<Object, Object, Object, Object> converter = determineTypeConverter(command.getFlags());
      command.setKey(converter.boxKey(key));
      command.setValue(converter.boxValue(command.getValue()));
      return invokeNext(ctx, command).thenApply((rCtx, rCommand, rv) -> converter.unboxValue(rv));
   }

   @Override
   public BasicInvocationStage visitPutMapCommand(InvocationContext ctx, PutMapCommand command) throws Throwable {
      if (ctx.isOriginLocal()) {
         Map<Object, Object> map = command.getMap();
         TypeConverter<Object, Object, Object, Object> converter = determineTypeConverter(command.getFlags());
         Map<Object, Object> convertedMap = new HashMap<>(map.size());
         for (Entry<Object, Object> entry : map.entrySet()) {
            convertedMap.put(converter.boxKey(entry.getKey()), converter.boxValue(entry.getValue()));
         }
         command.setMap(convertedMap);
      }
      // There is no return value for putAll so nothing to convert
      return invokeNext(ctx, command);
   }

   @Override
   public BasicInvocationStage visitGetKeyValueCommand(InvocationContext ctx, GetKeyValueCommand command) throws Throwable {
      if (!ctx.isOriginLocal()) return invokeNext(ctx, command);

      Object key = command.getKey();
      TypeConverter<Object, Object, Object, Object> converter = determineTypeConverter(command.getFlags());
      command.setKey(converter.boxKey(key));
      return invokeNext(ctx, command).thenApply((rCtx, rCommand, rv) -> {
         if (rv == null) {
            return rv;
         }
         return converter.unboxValue(rv);
      });
   }

   @Override
   public BasicInvocationStage visitGetCacheEntryCommand(InvocationContext ctx, GetCacheEntryCommand command)
         throws Throwable {
      if (!ctx.isOriginLocal()) return invokeNext(ctx, command);

      Object key = command.getKey();
      TypeConverter<Object, Object, Object, Object> converter = determineTypeConverter(command.getFlags());
      command.setKey(converter.boxKey(key));
      return invokeNext(ctx, command).thenApply((rCtx, rCommand, rv) -> {
         if (rv == null) {
            return rv;
         }
         CacheEntry entry = (CacheEntry) rv;
         Object returnValue = converter.unboxValue(entry.getValue());
         // Create a copy of the entry to avoid modifying the internal entry
         return entryFactory.create(entry.getKey(), returnValue, entry.getMetadata(), entry.getLifespan(),
               entry.getMaxIdle());
      });
   }

   @Override
   public BasicInvocationStage visitGetAllCommand(InvocationContext ctx, GetAllCommand command) throws Throwable {
      if (!ctx.isOriginLocal()) return invokeNext(ctx, command);

      Collection<?> keys = command.getKeys();
      TypeConverter<Object, Object, Object, Object> converter = determineTypeConverter(command.getFlags());
      Set<Object> boxedKeys = new LinkedHashSet<>(keys.size());
      for (Object key : keys) {
         boxedKeys.add(converter.boxKey(key));
      }
      command.setKeys(boxedKeys);
      return invokeNext(ctx, command).thenApply((rCtx, rCommand, rv) -> {
         if (rv == null) {
            return null;
         }

         if (command.isReturnEntries()) {
            Map<Object, CacheEntry> map = (Map<Object, CacheEntry>) rv;
            Map<Object, Object> unboxed = command.createMap();
            for (Entry<Object, CacheEntry> entry : map.entrySet()) {
               CacheEntry cacheEntry = entry.getValue();
               if (cacheEntry == null) {
                  unboxed.put(entry.getKey(), null);
               } else {
                  if (command.getRemotelyFetched() == null || !command.getRemotelyFetched().containsKey(entry.getKey())) {
                     unboxed.put(converter.unboxKey(entry.getKey()), entryFactory
                           .create(entry.getKey(), converter.unboxValue(cacheEntry.getValue()),
                                 cacheEntry.getMetadata(), cacheEntry.getLifespan(),
                                 cacheEntry.getMaxIdle()));
                  } else {
                     unboxed.put(converter.unboxKey(entry.getKey()), cacheEntry);
                  }
               }
            }
            return unboxed;
         } else {
            Map<Object, Object> map = (Map<Object, Object>) rv;
            Map<Object, Object> unboxed = command.createMap();
            for (Entry<Object, Object> entry : map.entrySet()) {
               Object value = entry.getValue();
               unboxed.put(converter.unboxKey(entry.getKey()), value == null ? null : converter.unboxValue(value));
            }
            return unboxed;
         }
      });
   }

   @Override
   public BasicInvocationStage visitReplaceCommand(InvocationContext ctx, ReplaceCommand command) throws Throwable {
      if (!ctx.isOriginLocal()) {
         return super.visitReplaceCommand(ctx, command);
      }
      Object key = command.getKey();
      TypeConverter<Object, Object, Object, Object> converter = determineTypeConverter(command.getFlags());
      Object oldValue = command.getOldValue();
      command.setKey(converter.boxKey(key));
      command.setOldValue(converter.boxValue(oldValue));
      command.setNewValue(converter.boxValue(command.getNewValue()));
      addVersionIfNeeded(command);
      return invokeNext(ctx, command).thenApply((rCtx, rCommand, rv) -> {
         // Return of conditional replace is not the value type, but boolean, so
         // apply an exception that applies to all servers, regardless of what's
         // stored in the value side
         if (oldValue != null && rv instanceof Boolean) return rv;

         return converter.unboxValue(rv);
      });
   }

   private void addVersionIfNeeded(MetadataAwareCommand cmd) {
      Metadata metadata = cmd.getMetadata();
      if (metadata.version() == null) {
         Metadata newMetadata = metadata.builder().version(versionGenerator.generateNew()).build();
         cmd.setMetadata(newMetadata);
      }
   }

   @Override
   public BasicInvocationStage visitRemoveCommand(InvocationContext ctx, RemoveCommand command) throws Throwable {
      if (!ctx.isOriginLocal()) {
         return super.visitRemoveCommand(ctx, command);
      }
      Object key = command.getKey();
      TypeConverter<Object, Object, Object, Object> converter = determineTypeConverter(command.getFlags());
      Object conditionalValue = command.getValue();
      command.setKey(converter.boxKey(key));
      command.setValue(converter.boxValue(conditionalValue));
      return invokeNext(ctx, command).thenApply((rCtx, rCommand, rv) -> {
         // Return of conditional remove is not the value type, but boolean, so
         // apply an exception that applies to all servers, regardless of what's
         // stored in the value side
         if (conditionalValue != null && rv instanceof Boolean) return rv;

         return ctx.isOriginLocal() ? converter.unboxValue(rv) : rv;
      });
   }

   @Override
   public BasicInvocationStage visitKeySetCommand(InvocationContext ctx, KeySetCommand command) throws Throwable {
      if (!ctx.isOriginLocal()) {
         return invokeNext(ctx, command);
      }

      TypeConverter<Object, Object, Object, Object> converter = determineTypeConverter(command.getFlags());
      return invokeNext(ctx, command).thenApply((rCtx, rCommand, rv) -> {
         CacheSet<K> set = (CacheSet<K>) rv;
         return new AbstractDelegatingKeyCacheSet<K, V>(Caches.getCacheWithFlags(cache, command), set) {
            @Override
            public CloseableIterator<K> iterator() {
               return new CloseableIteratorMapper<>(super.iterator(), k -> (K) converter.unboxKey(k));
            }

            @Override
            public CloseableSpliterator<K> spliterator() {
               return new IteratorAsSpliterator.Builder<>(iterator()).setEstimateRemaining(super.spliterator().estimateSize()).setCharacteristics(
                     Spliterator.CONCURRENT | Spliterator.DISTINCT | Spliterator.NONNULL).get();
            }

            @Override
            protected CacheStream<K> getStream(boolean parallel) {
               DistributionManager dm = cache.getAdvancedCache().getDistributionManager();
               // Note the stream has to deal with the boxed values - so we can't use our spliterator
               // as it already
               // unboxes them
               CloseableSpliterator<K> closeableSpliterator = super.spliterator();
               CacheStream<K> stream = new LocalCacheStream<>(
                     new KeyStreamSupplier<>(cache, dm != null ? dm.getConsistentHash() : null,
                           () -> StreamSupport.stream(closeableSpliterator, parallel)), parallel,
                     cache.getAdvancedCache().getComponentRegistry());
               // We rely on the fact that on close returns the same instance
               stream.onClose(closeableSpliterator::close);
               return new TypeConverterStream(stream, converter, entryFactory);
            }
         };
      });
   }

   @Override
   public BasicInvocationStage visitEntrySetCommand(InvocationContext ctx, EntrySetCommand command) throws Throwable {
      if (!ctx.isOriginLocal()) return invokeNext(ctx, command);

      return invokeNext(ctx, command).thenApply((rCtx, rCommand, rv) -> {
         CacheSet<CacheEntry<K, V>> set = (CacheSet<CacheEntry<K, V>>) rv;
         EntrySetCommand entrySetCommand = (EntrySetCommand) rCommand;
         TypeConverter<Object, Object, Object, Object> converter = determineTypeConverter(entrySetCommand.getFlags());
         return new AbstractDelegatingEntryCacheSet<K, V>(Caches.getCacheWithFlags(cache, command), set) {
            @Override
            public CloseableIterator<CacheEntry<K, V>> iterator() {
               return new TypeConverterIterator<>(super.iterator(), converter, entryFactory);
            }

            @Override
            public CloseableSpliterator<CacheEntry<K, V>> spliterator() {
               return new IteratorAsSpliterator.Builder<>(iterator()).setEstimateRemaining(super.spliterator().estimateSize()).setCharacteristics(
                     Spliterator.CONCURRENT | Spliterator.DISTINCT | Spliterator.NONNULL).get();
            }

            @Override
            protected CacheStream<CacheEntry<K, V>> getStream(boolean parallel) {
               DistributionManager dm = cache.getAdvancedCache().getDistributionManager();
               // Note the stream has to deal with the boxed values - so we can't use our spliterator
               // as it already

               // unboxes them
               CloseableSpliterator<CacheEntry<K, V>> closeableSpliterator = super.spliterator();
               CacheStream<CacheEntry<K, V>> stream = new LocalCacheStream<>(
                     new EntryStreamSupplier<>(cache, dm != null ? dm.getConsistentHash() : null,
                           () -> StreamSupport.stream(closeableSpliterator, parallel)), parallel,
                     cache.getAdvancedCache().getComponentRegistry());
               // We rely on the fact that on close returns the same instance
               stream.onClose(closeableSpliterator::close);
               return new TypeConverterStream(stream, converter, entryFactory);
            }
         };
      });
   }

   private static <K, V> CacheEntry<K, V> convert(CacheEntry<K, V> entry,
         TypeConverter<Object, Object, Object, Object> converter, InternalEntryFactory entryFactory) {
      K newKey = (K) converter.unboxKey(entry.getKey());
      V newValue = (V) converter.unboxValue(entry.getValue());
      // If either value changed then make a copy
      if (newKey != entry.getKey() || newValue != entry.getValue()) {
         return entryFactory.create(newKey, newValue, entry.getMetadata());
      }
      return entry;
   }

   private static class TypeConverterIterator<K, V> implements CloseableIterator<CacheEntry<K, V>> {
      private final CloseableIterator<CacheEntry<K, V>> iterator;
      private final TypeConverter<Object, Object, Object, Object> converter;
      private final InternalEntryFactory entryFactory;

      private TypeConverterIterator(CloseableIterator<CacheEntry<K, V>> iterator,
                                    TypeConverter<Object, Object, Object, Object> converter,
                                    InternalEntryFactory entryFactory) {
         this.iterator = iterator;
         this.converter = converter;
         this.entryFactory = entryFactory;
      }

      @Override
      public void close() {
         iterator.close();
      }

      @Override
      public boolean hasNext() {
         return iterator.hasNext();
      }

      @Override
      public CacheEntry<K, V> next() {
         CacheEntry<K, V> entry = iterator.next();
         return convert(entry, converter, entryFactory);
      }

      @Override
      public void remove() {
         iterator.remove();
      }
   }
}
