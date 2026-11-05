package ph.opendtv.ota;

/**
 * The logic the app loads from an over-the-air bundle (see {@link LogicBundles}). This interface is the contract
 * between the APK (the "shell") and the bundle: plain Java types only, so a bundle can be built without Android.
 *
 * Changing this interface is a shell change: bump {@link LogicBundles#SHELL_API} and ship a new APK, the same way
 * adding a native package in Expo needs a new build. Bundles only implement it.
 */
public interface AppLogic {
    /** Bundle version (1, 2, 3 ...), shown in Settings. */
    int version();

    /**
     * Full scan: is the service just found (serviceId, tsId, networkId) the same channel as one already in the list
     * (listed...)? Any ID is -1 when unknown; the saved channel list does not keep TS / network IDs yet, so the listed
     * ones are -1 for now. Issue #1: a service ID is only unique inside one multiplex.
     */
    boolean isSameService(int listedServiceId, int listedTsId, int listedNetworkId,
                          int serviceId, int tsId, int networkId);

    /**
     * Logo for a channel: the base name of an image in the app's assets/dtvlogo (e.g. "gma"), or null to let the app's
     * own rules decide. Channels get renamed and new ones start often, so the name matching lives here; the images
     * themselves ship with the APK.
     */
    String logoFor(String channelName, String networkName);
}
