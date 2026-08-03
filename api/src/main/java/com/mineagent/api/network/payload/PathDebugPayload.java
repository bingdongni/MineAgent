package com.mineagent.api.network.payload;

import java.util.List;
import java.util.UUID;

/**
 * Payload: path debug data sent from server to client for rendering
 * a navigation path overlay.
 *
 * @param companionId  the companion whose path is being debugged
 * @param pathNodes    the list of path node positions as [x, y, z] arrays
 * @param currentNode  the index of the current node the companion is moving toward
 * @param pathStatus   the status of the path (e.g. "navigating", "stuck", "arrived", "failed")
 */
public record PathDebugPayload(UUID companionId, List<double[]> pathNodes,
                                int currentNode, String pathStatus) {
    public PathDebugPayload {
        if (companionId == null) throw new IllegalArgumentException("companionId required");
        if (pathNodes == null) throw new IllegalArgumentException("pathNodes required");
        if (pathStatus == null) throw new IllegalArgumentException("pathStatus required");
        if (pathNodes.size() > 4096) throw new IllegalArgumentException("too many path nodes");
        List<double[]> copy = new java.util.ArrayList<>(pathNodes.size());
        for (double[] node : pathNodes) {
            if (node == null || node.length != 3
                    || !Double.isFinite(node[0]) || !Double.isFinite(node[1])
                    || !Double.isFinite(node[2])) {
                throw new IllegalArgumentException("each path node must contain three finite values");
            }
            copy.add(node.clone());
        }
        pathNodes = List.copyOf(copy);
        if (currentNode < -1 || currentNode >= pathNodes.size()) {
            throw new IllegalArgumentException("currentNode outside path");
        }
    }

    @Override
    public List<double[]> pathNodes() {
        // Arrays are mutable even inside an immutable List; do not expose the
        // record's internal coordinates to renderers or packet encoders.
        return pathNodes.stream().map(double[]::clone).toList();
    }
}
