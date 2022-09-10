package org.openslx.bwlp.sat;

import org.openslx.bwlp.sat.database.mappers.DbHelper;
import org.openslx.sat.thrift.version.Feature;

public class SupportedFeatures {

	private static String supportedFeatures = null;

	static {
		registerFeature(Feature.EXTEND_EXPIRED_VM);
		registerFeature(Feature.NETWORK_SHARES);
		registerFeature(Feature.MULTIPLE_HYPERVISORS);
		registerFeature(Feature.SERVER_SIDE_COPY);
		registerFeature(Feature.LECTURE_FILTER_LDAP);
		registerFeature(Feature.CONFIGURE_USB);
		// add docker feature, but check datebase if available
		registerFeatureIf(Feature.DOCKER_CONTAINER);
	}

	public static String getFeatureString() {
		return supportedFeatures;
	}

	private static void registerFeature(Feature feature) {
		if (supportedFeatures == null) {
			supportedFeatures = feature.name();
		} else {
			supportedFeatures += " " + feature.name();
		}
	}

	private static void registerFeatureIf(Feature feature) {
		switch (feature) {
			case DOCKER_CONTAINER:
				if (DbHelper.isDockerContainerAvailable())
					registerFeature(feature);
				break;

			default:
				break;
		}
	}

}
