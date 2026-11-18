package com.atakmap.android.localsvctemplate.plugin.coverage;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.atakmap.android.grg.GRGMapComponent;
import com.atakmap.android.hierarchy.HierarchyListReceiver;
import com.atakmap.android.importexport.ImportExportMapComponent;
import com.atakmap.android.importexport.ImportReceiver;
import com.atakmap.android.importexport.Importer;
import com.atakmap.android.importexport.ImporterManager;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.layers.LayersManagerBroadcastReceiver;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.io.IOProviderFactory;
import com.atakmap.coremap.log.Log;
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
 * GRG importer as an image overlay. ATAK draws it, lists it in Overlay Manager (where it can be
 * sent), and gives it its own radial - delete, show/hide, transparency, brightness, colour -
 * opened by tapping the overlay's outline. This class only writes the file, asks ATAK to import
 * or delete it, and shows the scale.
 *
 * <p>No ghosts: ATAK keeps imported overlays in its GRG database across restarts, and a restart
 * or crash never calls {@link #dispose()}. So every KMZ in our folder is a coverage overlay, and
 * {@link #remove()} deletes them all - on start, before each new map, and on stop.
 */
public final class CoverageMap {

    private static final String TAG = "CoverageMap";
    private static final String FOLDER = "tools/localsvctemplate";

    private final CoverageLegend legend;
    private String current;   // the KMZ name on the map, null if none

    // The user deleted the overlay from its radial or Overlay Manager: the scale goes too.
    private final BroadcastReceiver deleted = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String uri = intent.getStringExtra(ImportReceiver.EXTRA_URI);
            if (current != null && uri != null && uri.contains(current)) {
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
        remove();   // anything left by an earlier run
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
        // a new name each time, so ATAK's renderer never reuses the last image
        current = "Coverage_" + System.currentTimeMillis();
        File kmz = writeKmz(current, png, bounds);

        // ATAK imports it on its own import threads
        Intent i = new Intent(ImportExportMapComponent.ACTION_IMPORT_DATA);
        i.putExtra(ImportReceiver.EXTRA_CONTENT, GRGMapComponent.IMPORTER_CONTENT_TYPE);
        i.putExtra(ImportReceiver.EXTRA_MIME_TYPE, GRGMapComponent.IMPORTER_DEFAULT_MIME_TYPE);
        i.putExtra(ImportReceiver.EXTRA_URI, Uri.fromFile(kmz).toString());
        AtakBroadcast.getInstance().sendBroadcast(i);
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

    /**
     * Deletes every coverage overlay and its KMZ. Deleting from ATAK's GRG database is quick, so
     * it is done here, directly. A KMZ whose overlay couldn't be deleted is kept for next time.
     */
    public void remove() {
        current = null;
        legend.setVisible(false);
        Importer grg = ImporterManager.findImporter(GRGMapComponent.IMPORTER_CONTENT_TYPE,
                GRGMapComponent.IMPORTER_DEFAULT_MIME_TYPE);
        File[] files = IOProviderFactory.listFiles(FileSystemUtils.getItem(FOLDER));
        if (grg == null || files == null)
            return;
        for (File kmz : files) {
            if (!kmz.getName().endsWith(".kmz"))
                continue;
            try {
                grg.deleteData(Uri.fromFile(kmz), GRGMapComponent.IMPORTER_DEFAULT_MIME_TYPE);
                IOProviderFactory.delete(kmz);
            } catch (IOException e) {
                Log.e(TAG, "delete " + kmz + " failed", e);
            }
        }
        // Overlay Manager stops listing it
        AtakBroadcast.getInstance().sendBroadcast(
                new Intent(HierarchyListReceiver.REFRESH_HIERARCHY));
    }

    public void dispose() {
        AtakBroadcast.getInstance().unregisterReceiver(deleted);
        remove();
        legend.dispose();
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
