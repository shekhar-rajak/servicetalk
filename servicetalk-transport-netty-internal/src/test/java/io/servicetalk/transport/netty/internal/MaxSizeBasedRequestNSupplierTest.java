/*
 * Copyright © 2018 Apple Inc. and the ServiceTalk project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.servicetalk.transport.netty.internal;

import org.junit.Test;

import static io.servicetalk.transport.netty.internal.OverlappingCapacityAwareSupplier.SizeEstimator.defaultEstimator;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class MaxSizeBasedRequestNSupplierTest {

    @Test
    public void testNoRequestIfLowCapacity() {
        MaxSizeBasedRequestNSupplier supplier = new MaxSizeBasedRequestNSupplier(defaultEstimator(), 8);
        assertThat("Unexpected request-n.", supplier.getRequestNForCapacity(2), is(0L));
    }

    @Test
    public void testRequestNNoRecord() {
        MaxSizeBasedRequestNSupplier supplier = new MaxSizeBasedRequestNSupplier();
        assertThat("Unexpected requestN.", supplier.getRequestNForCapacity(8), is(1L));
    }

    @Test
    public void testRequestNWithRecord() {
        MaxSizeBasedRequestNSupplier supplier = new MaxSizeBasedRequestNSupplier();
        supplier.recordSize(1, 2);
        supplier.recordSize(3, 5);
        assertThat("Unexpected requestN.", supplier.getRequestNForCapacity(5), is(1L));
    }

    @Test
    public void testRepeatRequestNSameMaxSize() {
        MaxSizeBasedRequestNSupplier supplier = new MaxSizeBasedRequestNSupplier();
        supplier.recordSize(1, 2);
        supplier.recordSize(3, 5);
        assertThat("Unexpected requestN.", supplier.getRequestNForCapacity(5), is(1L));
        assertThat("Unexpected requestN.", supplier.getRequestNForCapacity(5), is(1L));
    }

    @Test
    public void testRepeatRequestNWithMaxSizeModified() {
        MaxSizeBasedRequestNSupplier supplier = new MaxSizeBasedRequestNSupplier();
        supplier.recordSize(1, 2);
        supplier.recordSize(3, 5);
        assertThat("Unexpected requestN.", supplier.getRequestNForCapacity(10), is(2L));
        supplier.recordSize(3, 8);
        assertThat("Unexpected requestN.", supplier.getRequestNForCapacity(8), is(1L));
    }

    @Test
    public void testMaxSizeOverwriteWindowFull() {
        MaxSizeBasedRequestNSupplier supplier = new MaxSizeBasedRequestNSupplier(defaultEstimator(), 8, 2);
        supplier.recordSize(1, 5);
        supplier.recordSize(3, 2);
        assertThat("Unexpected requestN.", supplier.getRequestNForCapacity(10), is(2L));
        supplier.recordSize(3, 5);
        assertThat("Unexpected requestN.", supplier.getRequestNForCapacity(5), is(1L));
    }

    @Test
    public void testLowerMaxSizeWindowFull() {
        MaxSizeBasedRequestNSupplier supplier = new MaxSizeBasedRequestNSupplier(defaultEstimator(), 8, 2);
        supplier.recordSize(1, 5);
        supplier.recordSize(3, 2);
        assertThat("Unexpected requestN.", supplier.getRequestNForCapacity(10), is(2L));
        supplier.recordSize(3, 3);
        assertThat("Unexpected requestN.", supplier.getRequestNForCapacity(3), is(1L));
    }

    @Test
    public void testLowestMaxSizeWindowFull() {
        MaxSizeBasedRequestNSupplier supplier = new MaxSizeBasedRequestNSupplier(defaultEstimator(), 8, 2);
        supplier.recordSize(1, 5);
        supplier.recordSize(3, 2);
        assertThat("Unexpected requestN.", supplier.getRequestNForCapacity(10), is(2L));
        supplier.recordSize(3, 1);
        assertThat("Unexpected requestN.", supplier.getRequestNForCapacity(6), is(3L));
    }
}
