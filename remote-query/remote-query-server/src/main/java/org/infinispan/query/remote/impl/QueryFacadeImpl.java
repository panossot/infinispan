package org.infinispan.query.remote.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.search.engine.impl.nullencoding.KeywordBasedNullCodec;
import org.hibernate.search.engine.impl.nullencoding.NullMarkerCodec;
import org.infinispan.AdvancedCache;
import org.infinispan.commons.logging.LogFactory;
import org.infinispan.protostream.ProtobufUtil;
import org.infinispan.protostream.SerializationContext;
import org.infinispan.protostream.WrappedMessage;
import org.infinispan.query.dsl.impl.BaseQuery;
import org.infinispan.query.remote.client.QueryRequest;
import org.infinispan.query.remote.client.QueryResponse;
import org.infinispan.query.remote.impl.logging.Log;
import org.infinispan.server.core.QueryFacade;
import org.kohsuke.MetaInfServices;

/**
 * A query facade implementation for both Lucene based queries and non-indexed queries.
 *
 * @author anistor@redhat.com
 * @since 6.0
 */
@MetaInfServices
public final class QueryFacadeImpl implements QueryFacade {

   private static final Log log = LogFactory.getLog(QueryFacadeImpl.class, Log.class);

   /**
    * A special hidden Lucene document field that holds the actual protobuf type name.
    */
   public static final String TYPE_FIELD_NAME = "$type$";

   /**
    * A special placeholder value that is indexed if the actual field value is null. This placeholder is needed because
    * Lucene does not index null values.
    */
   public static final String NULL_TOKEN = "_null_";

   public static final NullMarkerCodec NULL_TOKEN_CODEC = new KeywordBasedNullCodec(NULL_TOKEN);

   @Override
   public byte[] query(AdvancedCache<byte[], byte[]> cache, byte[] query) {
      RemoteQueryEngine queryEngine = SecurityActions.getCacheComponentRegistry(cache).getComponent(RemoteQueryEngine.class);
      if (queryEngine == null) {
         throw log.queryingNotEnabled(cache.getName());
      }

      SerializationContext serCtx = ProtobufMetadataManagerImpl.getSerializationContextInternal(cache.getCacheManager());
      try {
         QueryRequest request = ProtobufUtil.fromByteArray(serCtx, query, 0, query.length, QueryRequest.class);

         long startOffset = request.getStartOffset() == null ? -1 : request.getStartOffset();
         int maxResults = request.getMaxResults() == null ? -1 : request.getMaxResults();
         Map<String, Object> namedParameters = getNamedParameters(request);

         BaseQuery q = queryEngine.buildQuery(null, request.getJpqlString(), namedParameters, startOffset, maxResults);

         QueryResponse response = makeResponse(q);
         return ProtobufUtil.toByteArray(serCtx, response);
      } catch (IOException e) {
         throw log.errorExecutingQuery(e);
      }
   }

   private Map<String, Object> getNamedParameters(QueryRequest request) {
      List<QueryRequest.NamedParameter> namedParameters = request.getNamedParameters();
      if (namedParameters == null || namedParameters.isEmpty()) {
         return null;
      }
      Map<String, Object> params = new HashMap<>(namedParameters.size());
      for (QueryRequest.NamedParameter p : namedParameters) {
         params.put(p.getName(), p.getValue());
      }
      return params;
   }

   private QueryResponse makeResponse(BaseQuery q) {
      List<?> list = q.list();
      int numResults = list.size();
      String[] projection = q.getProjection();
      int projSize = projection != null ? projection.length : 0;
      List<WrappedMessage> results = new ArrayList<>(projSize == 0 ? numResults : numResults * projSize);

      for (Object o : list) {
         if (projSize == 0) {
            results.add(new WrappedMessage(o));
         } else {
            Object[] row = (Object[]) o;
            for (int i = 0; i < projSize; i++) {
               results.add(new WrappedMessage(row[i]));
            }
         }
      }

      QueryResponse response = new QueryResponse();
      response.setTotalResults(q.getResultSize());
      response.setNumResults(numResults);
      response.setProjectionSize(projSize);
      response.setResults(results);
      return response;
   }
}
