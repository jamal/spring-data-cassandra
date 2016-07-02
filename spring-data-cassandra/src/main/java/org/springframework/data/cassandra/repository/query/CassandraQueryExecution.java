/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.data.cassandra.repository.query;

import java.util.List;
import java.util.function.Function;

import org.springframework.core.convert.converter.Converter;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.mapping.CassandraMappingContext;
import org.springframework.data.cassandra.mapping.CassandraPersistentEntity;
import org.springframework.data.cassandra.mapping.CassandraPersistentProperty;
import org.springframework.data.domain.*;
import org.springframework.data.mapping.PersistentEntity;
import org.springframework.data.mapping.PersistentProperty;
import org.springframework.data.mapping.PersistentPropertyAccessor;
import org.springframework.data.mapping.PreferredConstructor;
import org.springframework.data.repository.query.ResultProcessor;
import org.springframework.data.repository.query.ReturnedType;
import org.springframework.data.util.StreamUtils;
import org.springframework.util.ClassUtils;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Query executions for Cassandra.
 *
 * @author Mark Paluch
 * @since 1.5
 */
interface CassandraQueryExecution {

	Object execute(String query, Class<?> type);

	/**
	 * {@link CassandraQueryExecution} for a Stream.
	 *
	 * @author Mark Paluch
	 */
	@RequiredArgsConstructor
	final class StreamExecution implements CassandraQueryExecution {

		private final @NonNull CassandraOperations operations;
		private final @NonNull Converter<Object, Object> resultProcessing;

		/* (non-Javadoc)
		 * @see org.springframework.data.cassandra.repository.query.CassandraQueryExecution#execute(java.lang.String, java.lang.Class)
		 */
		@Override
		public Object execute(String query, Class<?> type) {

			return StreamUtils.createStreamFromIterator(operations.stream(query, type)).map(new Function<Object, Object>() {

				@Override
				public Object apply(Object t) {
					return resultProcessing.convert(t);
				}
			});
		}
	}

	/**
	 * {@link CassandraQueryExecution} for collection returning queries.
	 *
	 * @author Mark Paluch
	 */
	@RequiredArgsConstructor
	final class CollectionExecution implements CassandraQueryExecution {

		private final @NonNull CassandraOperations operations;

		/* (non-Javadoc)
		 * @see org.springframework.data.cassandra.repository.query.CassandraQueryExecution#execute(java.lang.String, java.lang.Class)
		 */
		@Override
		public Object execute(String query, Class<?> type) {
			return operations.select(query, type);
		}
	}

	/**
	 * {@link CassandraQueryExecution} to return a single entity.
	 *
	 * @author Mark Paluch
	 */
	@RequiredArgsConstructor
	final class SingleEntityExecution implements CassandraQueryExecution {

		private final @NonNull CassandraOperations operations;

		/* (non-Javadoc)
		 * @see org.springframework.data.cassandra.repository.query.CassandraQueryExecution#execute(java.lang.String, java.lang.Class)
		 */
		@Override
		public Object execute(String query, Class<?> type) {
			return operations.selectOne(query, type);
		}
	}

	/**
	 * {@link CassandraQueryExecution} to return a {@link com.datastax.driver.core.ResultSet}.
	 *
	 * @author Mark Paluch
	 */
	@RequiredArgsConstructor
	final class ResultSetQuery implements CassandraQueryExecution {

		private final @NonNull CassandraOperations operations;

		/* (non-Javadoc)
		 * @see org.springframework.data.cassandra.repository.query.CassandraQueryExecution#execute(java.lang.String, java.lang.Class)
		 */
		@Override
		public Object execute(String query, Class<?> type) {
			return operations.query(query);
		}
	}

	/**
	 * {@link ContinuationQueryExecution} to return a {@link org.springframework.data.domain.Slice}.
	 */
	@RequiredArgsConstructor
	final class ContinuationQueryExecution implements CassandraQueryExecution {

		private final @NonNull CassandraOperations operations;
		private final @NonNull Continuable continuable;

		/* (non-Javadoc)
		 * @see org.springframework.data.cassandra.repository.query.CassandraQueryExecution#execute(java.lang.String, java.lang.Class)
		 */
		@Override
		public Object execute(String query, Class<?> type) {

			CassandraMappingContext context = operations.getConverter().getMappingContext();

			final int pageSize = continuable.getPageSize();
			final Object continuationValue = continuable.getContinuationValue();

			final CassandraPersistentEntity<?> entity = context.getPersistentEntity(type);
			CassandraPersistentProperty idProperty = entity.getIdProperty();

			// TODO: Use SimpleStatement instead of building the query
			String sliceQuery = query;
			if (continuationValue != null) {
				if (idProperty == null) {
					throw new IllegalArgumentException("Expected an Id for Continuation query");
				}

				// TODO: Properly deserialize continuationValue
				sliceQuery += String.format(" WHERE token(%s) > token(%s)", idProperty.getColumnName(), continuationValue.toString());
			}
			sliceQuery += " LIMIT " + String.valueOf(pageSize + 1);

			List result = operations.select(sliceQuery, type);

			boolean hasNext = result.size() > pageSize;
			Continuable nextContinuable = continuable;
			if (hasNext) {
				result = result.subList(0, pageSize);

				if (result.size() > 0) {
					Object last = result.get(result.size()-1);
					final PersistentPropertyAccessor accessor = entity.getPropertyAccessor(last);
					if (idProperty != null) {
						nextContinuable = new ContinuationRequest(accessor.getProperty(idProperty), pageSize);
					}
				}
			}

			return new ContinuationImpl<Object>(result, nextContinuable, hasNext);
		}
	}

	/**
	 * An {@link CassandraQueryExecution} that wraps the results of the given delegate with the given result processing.
	 *
	 * @author Mark Paluch
	 */
	@RequiredArgsConstructor
	final class ResultProcessingExecution implements CassandraQueryExecution {

		private final @NonNull CassandraQueryExecution delegate;
		private final @NonNull Converter<Object, Object> converter;

		/* (non-Javadoc)
		 * @see org.springframework.data.cassandra.repository.query.CassandraQueryExecution#execute(java.lang.String, java.lang.Class)
		 */
		@Override
		public Object execute(String query, Class<?> type) {
			return converter.convert(delegate.execute(query, type));
		}
	}

	/**
	 * A {@link Converter} to post-process all source objects using the given {@link ResultProcessor}.
	 *
	 * @author Mark Paluch
	 */
	@RequiredArgsConstructor
	final class ResultProcessingConverter implements Converter<Object, Object> {

		private final @NonNull ResultProcessor processor;

		/* (non-Javadoc)
		 * @see org.springframework.core.convert.converter.Converter#convert(java.lang.Object)
		 */
		@Override
		public Object convert(Object source) {

			ReturnedType returnedType = processor.getReturnedType();

			if (ClassUtils.isPrimitiveOrWrapper(returnedType.getReturnedType())) {
				return source;
			}

			return processor.processResult(source);
		}
	}
}
