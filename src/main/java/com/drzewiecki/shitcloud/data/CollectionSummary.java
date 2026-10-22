package com.drzewiecki.shitcloud.data;

/**
 * A collection of an app as the Data & history page lists it.
 *
 * @param documents documents it holds now
 * @param removed   documents removed from it whose last state is still in history
 */
public record CollectionSummary(String collection, long documents, long removed) {
}
