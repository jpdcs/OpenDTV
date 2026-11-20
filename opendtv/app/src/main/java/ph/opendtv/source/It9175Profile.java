package ph.opendtv.source;

/**
 * Per-dongle data for the IT9175 driver. The vendor app (PadTV) ships two builds of the same ITE SDK and picks one
 * by USB product id: 048d:275a / 275b use libpt275aDrv.so, 048d:e275 (PT275C) uses libPTx1Drv.so. Their tune code
 * is the same but the firmware image and the init register scripts differ, so each dongle gets its own set.
 */
final class It9175Profile {
    final String name;
    /** Firmware chunk sizes in download order (each sent with command 0x29). */
    final int[] fwChunks;
    private final String fwHex;
    final String[] initA0, initB0, tunePre0;
    final String[] initA1, initB1, tunePre1;
    /** band rows for OFDM 0x1dc = 0 / 1: startKHz, fb25, fbb5, fbb6, fbb7, fbb9a, fbb9b, fbba */
    final int[][] bands0, bands1;

    private It9175Profile(String name, int[] fwChunks, String fwHex, String[] initA0, String[] initB0,
                          String[] tunePre0, String[] initA1, String[] initB1, String[] tunePre1,
                          int[][] bands0, int[][] bands1) {
        this.name = name;
        this.fwChunks = fwChunks;
        this.fwHex = fwHex;
        this.initA0 = initA0;
        this.initB0 = initB0;
        this.tunePre0 = tunePre0;
        this.initA1 = initA1;
        this.initB1 = initB1;
        this.tunePre1 = tunePre1;
        this.bands0 = bands0;
        this.bands1 = bands1;
    }

    /** PT275 / GMA Now (048d:275a), data from libpt275aDrv.so. */
    static final It9175Profile PT275A = new It9175Profile("PT275 (libpt275aDrv)",
            It9175Firmware.CHUNKS, It9175Firmware.HEX,
            It9175Data.INIT_A0, It9175Data.INIT_B0, It9175Data.TUNE_PRE0,
            It9175Data.INIT_A1, It9175Data.INIT_B1, It9175Data.TUNE_PRE1,
            It9175Data.BANDS0, It9175Data.BANDS1);

    /** PT275C (048d:e275), data from libPTx1Drv.so. */
    static final It9175Profile PT275C = new It9175Profile("PT275C (libPTx1Drv)",
            It9175FirmwarePtx1.CHUNKS, It9175FirmwarePtx1.HEX,
            It9175DataPtx1.INIT_A0, It9175DataPtx1.INIT_B0, It9175DataPtx1.TUNE_PRE0,
            It9175DataPtx1.INIT_A1, It9175DataPtx1.INIT_B1, It9175DataPtx1.TUNE_PRE1,
            It9175DataPtx1.BANDS0, It9175DataPtx1.BANDS1);

    /** USB product ids (vendor 0x048d). Kept here, not in UsbTunerSource, so this class stays free of android.*. */
    static final int PID_PT275 = 0x275a;
    static final int PID_PT275C = 0xe275;

    /** Profile for a USB product id (vendor 0x048d), or null if the dongle is not supported. */
    static It9175Profile forProductId(int pid) {
        switch (pid) {
            case PID_PT275: return PT275A;
            case PID_PT275C: return PT275C;
            default: return null;
        }
    }

    /** The firmware bytes. */
    byte[] firmware() {
        byte[] out = new byte[fwHex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(fwHex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
