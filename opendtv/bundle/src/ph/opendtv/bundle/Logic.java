package ph.opendtv.bundle;

import ph.opendtv.ota.AppLogic;

/**
 * App logic that updates over the air (see app/src/main/java/ph/opendtv/ota/LogicBundles.java).
 * Build with tools/ota/build_bundle.sh; bump VERSION for every published bundle.
 */
public final class Logic implements AppLogic {
    private static final int VERSION = 2;

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public boolean isSameService(int listedServiceId, int listedTsId, int listedNetworkId,
                                 int serviceId, int tsId, int networkId) {
        // Bundle 2 (issue #1): a service ID is only unique inside one multiplex, and broadcasters reuse small IDs
        // (Solar's services are 1, 2, 3 ...). It is a duplicate only when it is the same multiplex received again on
        // another frequency: same TS ID, and the same network ID when both are known.
        if (listedServiceId != serviceId || listedTsId != tsId) return false;
        return listedNetworkId < 0 || networkId < 0 || listedNetworkId == networkId;
    }
}
