package com.atakmap.android.localsvctemplate.plugin.coverage;

import com.atakmap.android.importexport.send.SendDialog;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.io.IOProviderFactory;
import com.atakmap.coremap.maps.coords.GeoBounds;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Shares a coverage layer as a data package. The image goes in a KMZ as a KML GroundOverlay,
 * which every ATAK imports as an image overlay (a GRG) - receivers don't need this plugin. It
 * shows in their Overlay Manager, which has its own transparency control.
 */
public final class CoverageShare {

    private static final String FOLDER = "tools/localsvctemplate";

    private CoverageShare() {
    }

    /** Writes the KMZ and opens ATAK's send dialog (contacts, TAK Server, ...). UI thread. */
    public static void send(MapView mapView, CoverageLayer layer) throws IOException {
        byte[] png = layer.getPng();
        if (png == null)
            throw new IOException("nothing to share yet");
        String name = "Coverage " + new SimpleDateFormat("yyyy-MM-dd HHmmss", Locale.US).format(new Date());
        File kmz = writeKmz(name, png, layer.getBounds(), layer.getOpacity());
        new SendDialog.Builder(mapView)
                .setName(name)
                .addFile(kmz)
                .setImportOnReceive(true)    // receivers get it on their map straight away
                .show();
    }

    /** A KMZ is a zip: doc.kml, plus the image it refers to. */
    static File writeKmz(String name, byte[] png, GeoBounds b, float opacity) throws IOException {
        File dir = FileSystemUtils.getItem(FOLDER);
        if (!IOProviderFactory.exists(dir) && !IOProviderFactory.mkdirs(dir))
            throw new IOException("can't create " + dir);
        File kmz = new File(dir, name.replace(' ', '_') + ".kmz");

        // <color> is aabbggrr: white with the layer's opacity as alpha
        String kml = String.format(Locale.US,
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n"
                        + "  <GroundOverlay>\n"
                        + "    <name>%s</name>\n"
                        + "    <color>%02xffffff</color>\n"
                        + "    <Icon><href>coverage.png</href></Icon>\n"
                        + "    <LatLonBox>\n"
                        + "      <north>%.8f</north><south>%.8f</south>\n"
                        + "      <east>%.8f</east><west>%.8f</west>\n"
                        + "    </LatLonBox>\n"
                        + "  </GroundOverlay>\n"
                        + "</kml>\n",
                name, Math.round(opacity * 255), b.getNorth(), b.getSouth(), b.getEast(), b.getWest());

        try (OutputStream file = IOProviderFactory.getOutputStream(kmz);
                ZipOutputStream zip = new ZipOutputStream(file)) {
            zip.putNextEntry(new ZipEntry("doc.kml"));   // the first .kml in a KMZ is the document
            zip.write(kml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("coverage.png"));
            zip.write(png);
            zip.closeEntry();
        }
        return kmz;
    }
}
