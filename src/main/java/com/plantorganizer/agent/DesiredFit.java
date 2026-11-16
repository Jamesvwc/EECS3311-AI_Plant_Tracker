package com.plantorganizer.agent;

import com.plantorganizer.plants.DesiredPlant.FitVerdict;

/**
 * F09 fit check: is the wishlist plant a good choice for the space the user picked?
 *
 * @param careDataMissing true when no care data could be found, so the agent
 *                        could not judge and offers a web search instead
 */
public record DesiredFit(FitVerdict verdict, String explanation, boolean aiJudged, boolean careDataMissing) {
}
