package com.atakmap.android.localsvctemplate.plugin.coverage;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.atakmap.android.grg.GRGMapComponent;
import com.atakmap.android.importexport.ImportExportMapComponent;
import com.atakmap.android.importexport.ImportReceiver;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.layers.LayersManagerBroadcastReceiver;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.io.IOProviderFactory;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The one coverage map on the map, with its dBm scale. Showing a new one replaces the old.
 *
 * <pre>
 * CoverageMap coverage = new CoverageMap(mapView);        // plugin start
 * coverage.show(png, lowerLeft, upperRight);              // a box
 * coverage.show(png, centre, radiusMetres);               // or a point + radius
 * coverage.setScale(-50, -100);                           // optional; this is the default
 * coverage.dispose();                                     // plugin stop
 * </pre>
 *
 * ATAK does the work: the PNG is wrapped in a KMZ (a KML GroundOverlay) and handed to ATAK's
 * importer as an image overlay (a GRG). ATAK draws it, lists it in Overlay Manager (where it can
 * be sent), and gives it its own radial - delete, show/hide, transparency, brightness, colour -
 * opened by tapping the overlay's outline. Markers and shapes on top keep their own taps. This
 * class only writes the file, asks ATAK to import or delete it, and shows the scale.
 */
public final class CoverageMap {

    private static final String FOLDER = "tools/localsvctemplate";

    private final CoverageLegend legend;
    private File current;   // the KMZ on the map, null if none

    // The user deleted the overlay from its radial or Overlay Manager: the scale goes too.
    private final BroadcastReceiver deleted = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String uri = intent.getStringExtra(ImportReceiver.EXTRA_URI);
            if (current != null && uri != null && uri.contains(current.getName())) {
                current = null;
                legend.setVisible(false);
            }
        }
    };

    public CoverageMap(MapView mapView) {
        legend = new CoverageLegend(mapView);
        setScale(-50, -100);
        AtakBroadcast.DocumentedIntentFilter filter = new AtakBroadcast.DocumentedIntentFilter();
        filter.addAction(LayersManagerBroadcastReceiver.ACTION_GRG_DELETE);      // its radial
        filter.addAction(ImportExportMapComponent.ACTION_DELETE_DATA);           // Overlay Manager
        AtakBroadcast.getInstance().registerReceiver(deleted, filter);
    }

    /** Shows a PNG covering the box lowerLeft..upperRight, replacing any coverage shown. */
    public void show(byte[] png, GeoPoint lowerLeft, GeoPoint upperRight) throws IOException {
        show(png, new GeoBounds(upperRight.getLatitude(), lowerLeft.getLongitude(),
                lowerLeft.getLatitude(), upperRight.getLongitude()));
    }

    /** Shows a PNG covering the square radiusM either side of centre (N, S, E and W). */
    public void show(byte[] png, GeoPoint centre, double radiusM) throws IOException {
        double dLat = radiusM / 111_320.0;
        double dLon = dLat / Math.cos(Math.toRadians(centre.getLatitude()));
        show(png, new GeoBounds(centre.getLatitude() + dLat, centre.getLongitude() - dLon,
                centre.getLatitude() - dLat, centre.getLongitude() + dLon));
    }

    /** Shows a PNG covering the bounds, replacing any coverage shown. */
    public void show(byte[] png, GeoBounds bounds) throws IOException {
        remove();
        // a new file name each time, so ATAK never mixes this import up with the last delete
        current = writeKmz("Coverage_" + System.currentTimeMillis(), png, bounds);
        sendToImporter(ImportExportMapComponent.ACTION_IMPORT_DATA, current);
        legend.setVisible(true);
    }

    /** The scale shown in the corner, with the default colours (red strongest .. blue weakest). */
    public void setScale(double strongDbm, double weakDbm) {
        setScale(strongDbm, weakDbm, CoverageLegend.DEFAULT_COLOURS);
    }

    /** @param colours strongest to weakest; must match how the PNG was coloured */
    public void setScale(double strongDbm, double weakDbm, int[] colours) {
        legend.setScale(strongDbm, weakDbm, colours);
    }

    public void remove() {
        if (current == null)
            return;
        File old = current;
        current = null;
        legend.setVisible(false);
        sendToImporter(ImportExportMapComponent.ACTION_DELETE_DATA, old);
        IOProviderFactory.delete(old);
    }

    public void dispose() {
        AtakBroadcast.getInstance().unregisterReceiver(deleted);
        remove();
        legend.dispose();
    }

    /** Asks ATAK's importer to import the file as an image overlay (GRG), or to delete it. */
    private static void sendToImporter(String action, File kmz) {
        Intent i = new Intent(action);
        i.putExtra(ImportReceiver.EXTRA_CONTENT, GRGMapComponent.IMPORTER_CONTENT_TYPE);
        i.putExtra(ImportReceiver.EXTRA_MIME_TYPE, GRGMapComponent.IMPORTER_DEFAULT_MIME_TYPE);
        i.putExtra(ImportReceiver.EXTRA_URI, Uri.fromFile(kmz).toString());
        i.putExtra(ImportReceiver.EXTRA_SHOW_NOTIFICATIONS, false);
        i.putExtra(ImportReceiver.EXTRA_ZOOM_TO_FILE, false);
        AtakBroadcast.getInstance().sendBroadcast(i);
    }

    /** A KMZ is a zip: doc.kml, plus the image it refers to. */
    private static File writeKmz(String name, byte[] png, GeoBounds b) throws IOException {
        File dir = FileSystemUtils.getItem(FOLDER);
        if (!IOProviderFactory.exists(dir) && !IOProviderFactory.mkdirs(dir))
            throw new IOException("can't create " + dir);
        File kmz = new File(dir, name + ".kmz");

        String kml = String.format(Locale.US,
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n"
                        + "  <GroundOverlay>\n"
                        + "    <name>%s</name>\n"
                        + "    <Icon><href>coverage.png</href></Icon>\n"
                        + "    <LatLonBox>\n"
                        + "      <north>%.8f</north><south>%.8f</south>\n"
                        + "      <east>%.8f</east><west>%.8f</west>\n"
                        + "    </LatLonBox>\n"
                        + "  </GroundOverlay>\n"
                        + "</kml>\n",
                name, b.getNorth(), b.getSouth(), b.getEast(), b.getWest());

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
