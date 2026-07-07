/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.glaforge.agybrainviz;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.util.List;

/**
 * Builds the cross-session {@link InsightsReport} for a source by handing the sessions gathered by
 * {@link SessionCollector} to the pure {@link FleetInsights} aggregator.
 */
@Singleton
public class InsightsService {

    private final SessionCollector collector;

    @Inject
    public InsightsService(SessionCollector collector) {
        this.collector = collector;
    }

    /** Cap the drilled-in session list so a very common item stays responsive to render. */
    static final int MAX_DRILLDOWN = 50;

    public InsightsReport forFlavor(String flavor) throws IOException {
        SessionCollector.Collected collected = collector.collect(flavor);
        return FleetInsights.aggregate(flavor, collected.totalSessionCount(), collected.sessions());
    }

    /** The sessions behind one tally item (which tool/error/recommendation/issue it came from). */
    public DrilldownResult drilldown(String flavor, String category, String key)
        throws IOException {
        SessionCollector.Collected collected = collector.collect(flavor);
        List<SessionRef> all = FleetInsights.sessionsFor(category, key, collected.sessions());
        List<SessionRef> capped = all.stream().limit(MAX_DRILLDOWN).toList();
        return new DrilldownResult(category, key, all.size(), capped);
    }
}
