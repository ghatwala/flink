/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.contrib.streaming.state;

import org.apache.flink.runtime.memory.OpaqueMemoryResource;
import org.apache.flink.util.IOUtils;

import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;

import javax.annotation.Nullable;

import java.util.ArrayList;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * The container for RocksDB resources, including predefined options, option factory and
 * shared resource among instances.
 *
 * <p>This should be the only entrance for {@link RocksDBStateBackend} to get RocksDB options,
 * and should be properly (and necessarily) closed to prevent resource leak.
 */
public final class RocksDBResourceContainer implements AutoCloseable {

	/** The pre-configured option settings. */
	private final PredefinedOptions predefinedOptions;

	/** The options factory to create the RocksDB options. */
	@Nullable
	private final RocksDBOptionsFactory optionsFactory;

	/** The shared resource among RocksDB instances. This resource is not part of the 'handlesToClose',
	 * because the handles to close are closed quietly, whereas for this one, we want exceptions to be reported. */
	@Nullable
	private final OpaqueMemoryResource<RocksDBSharedResources> sharedResources;

	/** The handles to be closed when the container is closed. */
	private final ArrayList<AutoCloseable> handlesToClose;

	public RocksDBResourceContainer() {
		this(PredefinedOptions.DEFAULT, null, null);
	}

	public RocksDBResourceContainer(PredefinedOptions predefinedOptions, @Nullable RocksDBOptionsFactory optionsFactory) {
		this(predefinedOptions, optionsFactory, null);
	}

	public RocksDBResourceContainer(
		PredefinedOptions predefinedOptions,
		@Nullable RocksDBOptionsFactory optionsFactory,
		@Nullable OpaqueMemoryResource<RocksDBSharedResources> sharedResources) {

		this.predefinedOptions = checkNotNull(predefinedOptions);
		this.optionsFactory = optionsFactory;
		this.sharedResources = sharedResources;
		this.handlesToClose = new ArrayList<>();
	}

	/**
	 * Gets the RocksDB {@link DBOptions} to be used for RocksDB instances.
	 */
	public DBOptions getDbOptions() {
		// initial options from pre-defined profile
		DBOptions opt = predefinedOptions.createDBOptions(handlesToClose);
		handlesToClose.add(opt);

		// add user-defined options factory, if specified
		if (optionsFactory != null) {
			opt = optionsFactory.createDBOptions(opt, handlesToClose);
		}

		// add necessary default options
		opt = opt.setCreateIfMissing(true);

		return opt;
	}

	/**
	 * Gets the RocksDB {@link ColumnFamilyOptions} to be used for all RocksDB instances.
	 */
	ColumnFamilyOptions getColumnOptions() {
		// initial options from pre-defined profile
		ColumnFamilyOptions opt = predefinedOptions.createColumnOptions(handlesToClose);
		handlesToClose.add(opt);

		// add user-defined options, if specified
		if (optionsFactory != null) {
			opt = optionsFactory.createColumnOptions(opt, handlesToClose);
		}

		return opt;
	}

	RocksDBNativeMetricOptions getMemoryWatcherOptions(RocksDBNativeMetricOptions defaultMetricOptions) {
		return optionsFactory == null
				? defaultMetricOptions
				: optionsFactory.createNativeMetricsOptions(defaultMetricOptions);
	}

	PredefinedOptions getPredefinedOptions() {
		return predefinedOptions;
	}

	@Nullable
	RocksDBOptionsFactory getOptionsFactory() {
		return optionsFactory;
	}

	@Override
	public void close() throws Exception {
		handlesToClose.forEach(IOUtils::closeQuietly);
		handlesToClose.clear();

		if (sharedResources != null) {
			sharedResources.close();
		}
	}
}
