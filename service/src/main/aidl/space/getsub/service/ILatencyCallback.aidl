// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service;

/**
 * Streams one measurement run's results from :bg back to :main.
 *
 * `oneway` for the same reason ITunnelCallback is: a blocking callback lets a
 * slow UI stall the service (§5.3).
 *
 * Every method carries runId. Cancellation cannot interrupt an in-flight
 * libXray ping — it is a blocking call into Go with no interrupt — so results
 * from a superseded run keep arriving after the next run has started. The stamp
 * is what lets :main drop them instead of writing a stale number onto a row that
 * is currently being retested.
 *
 * `outcome` is a LatencyOutcome ordinal, never a message string: libXray's ping
 * errors quote the config that produced them, which carries the address, UUID
 * and REALITY key (§5.6). An ordinal is safe here for the reason
 * ConnectionStateParcel's already are — this never outlives a single bind, so
 * reordering the enum cannot corrupt anything persisted.
 */
oneway interface ILatencyCallback {
    void onResult(long runId, long profileId, int delayMillis, int outcome);

    void onFinished(long runId);
}
