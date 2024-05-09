/*
 * Copyright 2024 the original author or authors.
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

package org.gradle.cache.internal;

import org.gradle.internal.operations.BuildOperationType;

import java.time.Instant;

public final class CacheCleanupBuildOperationType implements BuildOperationType<CacheCleanupBuildOperationType.Details, CacheCleanupBuildOperationType.Result> {

    /**
     * Sent when the cache is cleaned up.
     */
    public interface Details {
        /**
         * True when a cleanup is due and will be attempted.
         */
        boolean isCleanupRequired();
    }

    /**
     * Sent after the cache has been cleaned up or the cleanup has been skipped.
     */
    public interface Result {

        /**
         * True when cache was cleaned up because a cleanup was due.
         */
        boolean isCleanupPerformed();

        /**
         * The number of cache entries deleted during this clean up.
         */
        long getDeletedEntriesCount();

        /**
         * The timestamp the last cleanup was performed.
         */
        Instant getPreviousCleanupTime();
    }

}
