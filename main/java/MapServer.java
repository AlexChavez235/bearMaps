import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;
import java.util.List;
/* Maven is used to pull in these dependencies. */
import com.google.gson.Gson;

import static spark.Spark.*;

/**
 * This MapServer class is the entry point for running the JavaSpark web server for the BearMaps
 * application project, receiving API calls, handling the API call processing, and generating
 * requested images and routes.
 */
public class MapServer {
    /**
     * The root upper left/lower right longitudes and latitudes represent the bounding box of
     * the root tile, as the images in the img/ folder are scraped.
     * Longitude == x-axis; latitude == y-axis.
     */
    public static final double ROOT_ULLAT = 37.892195547244356, ROOT_ULLON = -122.2998046875,
            ROOT_LRLAT = 37.82280243352756, ROOT_LRLON = -122.2119140625;
    /** Each tile is 256x256 pixels. */
    public static final int TILE_SIZE = 256;
    /** HTTP failed response. */
    private static final int HALT_RESPONSE = 403;
    /** Route stroke information: typically roads are not more than 5px wide. */
    public static final float ROUTE_STROKE_WIDTH_PX = 5.0f;
    /** Route stroke information: Cyan with half transparency. */
    public static final Color ROUTE_STROKE_COLOR = new Color(108, 181, 230, 200);
    /** The tile images are in the IMG_ROOT folder. */
    private static final String IMG_ROOT = "img/";
    /**
     * The OSM XML file path. Downloaded from <a href="http://download.bbbike.org/osm/">here</a>
     * using custom region selection.
     **/
    private static final String OSM_DB_PATH = "berkeley.osm";
    /**
     * Each raster request to the server will have the following parameters
     * as keys in the params map accessible by,
     * i.e., params.get("ullat") inside getMapRaster(). <br>
     * ullat -> upper left corner latitude,<br> ullon -> upper left corner longitude, <br>
     * lrlat -> lower right corner latitude,<br> lrlon -> lower right corner longitude <br>
     * w -> user viewport window width in pixels,<br> h -> user viewport height in pixels.
     **/
    private static final String[] REQUIRED_RASTER_REQUEST_PARAMS = {"ullat", "ullon", "lrlat",
        "lrlon", "w", "h"};
    /**
     * Each route request to the server will have the following parameters
     * as keys in the params map.<br>
     * start_lat -> start point latitude,<br> start_lon -> start point longitude,<br>
     * end_lat -> end point latitude, <br>end_lon -> end point longitude.
     **/
    private static final String[] REQUIRED_ROUTE_REQUEST_PARAMS = {"start_lat", "start_lon",
        "end_lat", "end_lon"};
    /* Define any static variables here. Do not define any instance variables of MapServer. */
    private static GraphDB g;
    private static LinkedList<Long> route;

    /**
     * Place any initialization statements that will be run before the server main loop here.
     * Do not place it in the main function. Do not place initialization code anywhere else.
     * This is for testing purposes, and you may fail tests otherwise.
     **/
    public static void initialize() {
        g = new GraphDB("berkeley.osm");
    }

    public static void main(String[] args) {
        initialize();
        staticFileLocation("/page");
        /* Allow for all origin requests (since this is not an authenticated server, we do not
         * care about CSRF).  */
        before((request, response) -> {
            response.header("Access-Control-Allow-Origin", "*");
            response.header("Access-Control-Request-Method", "*");
            response.header("Access-Control-Allow-Headers", "*");
        });

        /* Define the raster endpoint for HTTP GET requests. I use anonymous functions to define
         * the request handlers. */
        get("/raster", (req, res) -> {
            HashMap<String, Double> params =
                    getRequestParams(req, REQUIRED_RASTER_REQUEST_PARAMS);
            /* The png image is written to the ByteArrayOutputStream */
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            /* getMapRaster() does almost all the work for this API call */
            Map<String, Object> rasteredImgParams = getMapRaster(params, os);
            /* On an image query success, add the image data to the response */
            if (rasteredImgParams.containsKey("query_success")
                    && (Boolean) rasteredImgParams.get("query_success")) {
                String encodedImage = Base64.getEncoder().encodeToString(os.toByteArray());
                rasteredImgParams.put("b64_encoded_image_data", encodedImage);
            }
            /* Encode response to Json */
            Gson gson = new Gson();
            return gson.toJson(rasteredImgParams);
        });

        /* Define the routing endpoint for HTTP GET requests. */
        get("/route", (req, res) -> {
            HashMap<String, Double> params =
                    getRequestParams(req, REQUIRED_ROUTE_REQUEST_PARAMS);
            LinkedList<Long> route = findAndSetRoute(params);
            return !route.isEmpty();
        });

        /* Define the API endpoint for clearing the current route. */
        get("/clear_route", (req, res) -> {
            clearRoute();
            return true;
        });

        /* Define the API endpoint for search */
        get("/search", (req, res) -> {
            Set<String> reqParams = req.queryParams();
            String term = req.queryParams("term");
            Gson gson = new Gson();
            /* Search for actual location data. */
            if (reqParams.contains("full")) {
                List<Map<String, Object>> data = getLocations(term);
                return gson.toJson(data);
            } else {
                /* Search for prefix matching strings. */
                List<String> matches = getLocationsByPrefix(term);
                return gson.toJson(matches);
            }
        });

        /* Define map application redirect */
        get("/", (request, response) -> {
            response.redirect("/map.html", 301);
            return true;
        });
    }

    /**
     * Validate & return a parameter map of the required request parameters.
     * Requires that all input parameters are doubles.
     * @param req HTTP Request
     * @param requiredParams TestParams to validate
     * @return A populated map of input parameter to it's numerical value.
     */
    private static HashMap<String, Double> getRequestParams(
            spark.Request req, String[] requiredParams) {
        Set<String> reqParams = req.queryParams();
        HashMap<String, Double> params = new HashMap<>();
        for (String param : requiredParams) {
            if (!reqParams.contains(param)) {
                halt(HALT_RESPONSE, "Request failed - parameters missing.");
            } else {
                try {
                    params.put(param, Double.parseDouble(req.queryParams(param)));
                } catch (NumberFormatException e) {
                    e.printStackTrace();
                    halt(HALT_RESPONSE, "Incorrect parameters - provide numbers.");
                }
            }
        }
        return params;
    }

    public int getDepth(Map<String, Double> params) {
        double xDist = params.get("ullon") - params.get("lrlon");
        // calculate the distance per pixel
        double dpp = Math.abs(xDist / params.get("w"));
        int depth = 0;
        double tDPP = (ROOT_LRLON-ROOT_ULLON)/(TILE_SIZE);
        // calculate the depth(in the quad tree) of the images to be rastered
        while(tDPP>dpp) {
            depth++;
            tDPP = (ROOT_LRLON-ROOT_ULLON)/((int)(Math.pow(2, depth))*TILE_SIZE);
        }
        // maximum depth of the quadtree is 7
        // zooming in any further does nothing
        return Math.min(depth, 7);
    }

    public int getImageHeight(ArrayList<QuadTree.QTreeNode> tiles) {
        int height = 1; 
        // calculate the height (in tiles) of the buffered image
        for (int i = 1; i<tiles.size(); i++) {
            if (tiles.get(i).getUllat() < tiles.get(i - 1).getUllat()) {
                height++;
            } 
        }
        return height;
    }

    public void drawTiles(ArrayList<QuadTree.QTreeNode> tiles, Graphics graph) {
        // x and y indicate the postion of the top-left of the image
        int x = 0, y = 0;
            
        for (int i = 0; i < tiles.size(); i++) {
            QuadTree.QTreeNode node = tiles.get(i);
            BufferedImage bi = ImageIO.read(new File("img/" + node.getFileName() + ".png"));
            // draw image bi with (x, y) as coordinate of top-left of the image
            graph.drawImage(bi, x, y, null);
            // every image is 256x256
            x += 256;
            if (i<tiles.size()-1) {
                if (tiles.get(i+1).getUllat() < node.getUllat()) {
                    // draw the next image on a new row
                    x = 0;
                    y += 256;
                }
            }
        }
    }

    // use Dijkstra's algorithm to find route between startNode and endNode
    public LinkedList<Long> AStarSearch(Node startNode, Node endNode) {
        route  = new LinkedList<>();
        HashSet<Node> visited = new HashSet<>();
        HashMap<Node, Double> distances = new HashMap<>();
        HashMap<Node, Node> prev = new HashMap<>();
        PriorityQueue<Node> fringe = new PriorityQueue<>();
        fringe.add(startNode);
        distances.put(startNode, 0.0);
        while (fringe.size()>0) {
            Node v = fringe.poll();
            if (visited.contains(v)) {
                continue;
            }
            visited.add(v);
            if(v==endNode) {
                break;
            }
            for (Connections c: v.connectionsSet) {
                if(!distances.containsKey(c.n2) || distances.get(c.n2) > distances.get(v) + Math.sqrt((c.n2.lon-v.lon)*(c.n2.lon-v.lon)+(c.n2.lat-v.lat)*(c.n2.lat-v.lat))) {
                    distances.put(c.n2, distances.get(v) + Math.sqrt((c.n2.lon-v.lon)*(c.n2.lon-v.lon)+(c.n2.lat-v.lat)*(c.n2.lat-v.lat)));
                    c.n2.setFn(Math.sqrt((c.n2.lon-endNode.lon)*(c.n2.lon-endNode.lon)+(c.n2.lat-endNode.lat)*(c.n2.lat-endNode.lat)) + distances.get(c.n2));
                    fringe.add(c.n2);
                    prev.put(c.n2, v);
                }

            }
        }
        Node N = endNode;
        while(prev.get(N) != startNode) {
            route.addFirst(N.id);
            N = prev.get(N);
        }
        route.addFirst(N.id);
        route.addFirst(startNode.id);
        return route;

    }

    // finds the closest Location to the specified longitude and latitude
    public Node findClosest(double lon, double lat) {
        Node closestNode = null;
        double dist = Double.MAX_VALUE;
        // iterate through all locations to find the closest
        for (Map.Entry<Long, Node> entry: g.getConnected().entrySet()) {
            Node n = entry.getValue();
            // euclidean distance
            double newDist = Math.sqrt((startLon-n.lon)*(startLon-n.lon)+(startLat-n.lat)*(startLat-n.lat));
            // check if we found a closer location
            if (newDist < dist) {
                startNode = n;
                dist = newDist;
            }
        }
        return closestNode;

    }


    /**
     * Handles raster API calls, queries for tiles and rasters the full image. <br>
     * <p>
     *     The rastered photo must have the following properties:
     *     <ul>
     *         <li>Has dimensions of at least w by h, where w and h are the user viewport width
     *         and height.</li>
     *         <li>The tiles collected must cover the most longitudinal distance per pixel
     *         possible, while still covering less than or equal to the amount of
     *         longitudinal distance per pixel in the query box for the user viewport size. </li>
     *         <li>Contains all tiles that intersect the query bounding box that fulfill the
     *         above condition.</li>
     *         <li>The tiles must be arranged in-order to reconstruct the full image.</li>
     *         <li>If a current route exists, lines of width ROUTE_STROKE_WIDTH_PX and of color
     *         ROUTE_STROKE_COLOR are drawn between all nodes on the route in the rastered photo.
     *         </li>
     *     </ul>
     *     Additional image about the raster is returned and is to be included in the Json response.
     * </p>
     * @param params Map of the HTTP GET request's query parameters - the query bounding box and
     *               the user viewport width and height.
     * @param os     An OutputStream that the resulting png image should be written to.
     * @return A map of parameters for the Json response as specified:
     * "raster_ul_lon" -> Double, the bounding upper left longitude of the rastered image <br>
     * "raster_ul_lat" -> Double, the bounding upper left latitude of the rastered image <br>
     * "raster_lr_lon" -> Double, the bounding lower right longitude of the rastered image <br>
     * "raster_lr_lat" -> Double, the bounding lower right latitude of the rastered image <br>
     * "raster_width"  -> Double, the width of the rastered image <br>
     * "raster_height" -> Double, the height of the rastered image <br>
     * "depth"         -> Double, the 1-indexed quadtree depth of the nodes of the rastered image.
     * Can also be interpreted as the length of the numbers in the image string. <br>
     * "query_success" -> Boolean, whether an image was successfully rastered. <br>
     * @see #REQUIRED_RASTER_REQUEST_PARAMS
     */
    public static Map<String, Object> getMapRaster(Map<String, Double> params, OutputStream os) {
        // this Quadtree contains all "zoom levels"
        QuadTree map = new QuadTree();
        // calculates the depth of the images to be rastered
        int depth = getDepth(params);
        // stores the images to be rastered
        ArrayList<QuadTree.QTreeNode> tiles = new ArrayList<>();
        // collect the correct images based on lat and lon values of query window
        map.traverseDepth(map.root(), depth, params.get("ullat"), params.get("lrlat"), params.get("ullon"), params.get("lrlon"), tiles);
        // sorts the tiles by longitude and latitude
        Collections.sort(tiles);
        // must contain the parameters for the json response
        HashMap<String, Object> rasteredImageParams = new HashMap<>();
        try {
            // calculate the height in terms of tiles of the image to be rastered
            int height = getImageHeight(tiles); 
            BufferedImage im = new BufferedImage((tiles.size()/height) * 256, height * 256, BufferedImage.TYPE_INT_RGB);
            Graphics graph = im.getGraphics();
            // x and y indicate the postion of the top-left of the image
            int x = 0, y = 0;
            // draw all the tiles on a BufferedImage
            drawTiles(tiles, graph);

            double raster_height = height * 256;
            double raster_width = (tiles.size()/height) * 256;
            double wDDP = (tiles.get(tiles.size()-1).getLrlon()-tiles.get(0).getUllon())/raster_width;
            double hDDP = (tiles.get(0).getUllat()-tiles.get(tiles.size()-1).getLrlat())/raster_height;
            // draws a route if user requests a route between two locations
            if (route != null) {
                Stroke stroke = new BasicStroke(MapServer.ROUTE_STROKE_WIDTH_PX, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
                ((Graphics2D) graph).setStroke(stroke);
                graph.setColor(MapServer.ROUTE_STROKE_COLOR);
                for (int i = 0; i<route.size()-1;i++) {
                    Node node1 = g.getConnected().get(route.get(i));
                    Node node2 = g.getConnected().get(route.get(i+1));
                    // calculate relative lon and lat for node1
                    int lon1 = (int) Math.floor((node1.lon-tiles.get(0).getUllon())/wDDP);
                    int lat1 = (int) Math.floor((tiles.get(0).getUllat()-node1.lat)/hDDP);
                    // calculate relative lon and lat for node2
                    int lon2 = (int) Math.floor((node2.lon-tiles.get(0).getUllon())/wDDP);
                    int lat2 = (int) Math.floor((tiles.get(0).getUllat()-node2.lat)/hDDP);
                    graph.drawLine(lon1, lat1, lon2, lat2);
                }
            }
            // required parameters
            rasteredImageParams.put("raster_ul_lon", tiles.get(0).getUllon());
            rasteredImageParams.put("raster_ul_lat", tiles.get(0).getUllat());
            rasteredImageParams.put("raster_lr_lon", tiles.get(tiles.size()-1).getLrlon());
            rasteredImageParams.put("raster_lr_lat", tiles.get(tiles.size()-1).getLrlat());
            rasteredImageParams.put("raster_width", (int) raster_width);
            rasteredImageParams.put("raster_height", (int) raster_height);
            rasteredImageParams.put("depth", depth);
            rasteredImageParams.put("query_success", true);
            // write the buffered image to the output stream
            ImageIO.write(im, "png", os);
        }
        catch(IOException e) {
            System.out.println(e);
        }
        return rasteredImageParams;
    }

    /**
     * Searches for the shortest route satisfying the input request parameters, sets it to be the
     * current route, and returns a <code>LinkedList</code> of the route's node ids for testing
     * purposes. <br>
     * The route should start from the closest node to the start point and end at the closest node
     * to the endpoint. Distance is defined as the euclidean between two points (lon1, lat1) and
     * (lon2, lat2).
     * @param params from the API call described in REQUIRED_ROUTE_REQUEST_PARAMS
     * @return A LinkedList of node ids from the start of the route to the end.
     */
    public static LinkedList<Long> findAndSetRoute(Map<String, Double> params) {
        double startLon = params.get("start_lon");
        double startLat = params.get("start_lat");
        double endLon = params.get("end_lon");
        double endLat = params.get("end_lat");
        Node startNode = findClosest(startLon, startLat);
        Node endNode = findClosest(endLon, endLat);
        return AStarSearch(startNode, endNode);
    }

    /**
     * Clear the current found route, if it exists.
     */
    public static void clearRoute() {
        route = null;
    }

    /**
     * In linear time, collect all the names of OSM locations that prefix-match the query string.
     * @param prefix Prefix string to be searched for. Could be any case, with our without
     *               punctuation.
     * @return A <code>List</code> of the full names of locations whose cleaned name matches the
     * cleaned <code>prefix</code>.
     */
    public static List<String> getLocationsByPrefix(String prefix) {
        return new LinkedList<>();
    }

    /**
     * Collect all locations that match a cleaned <code>locationName</code>, and return
     * information about each node that matches.
     * @param locationName A full name of a location searched for.
     * @return A list of locations whose cleaned name matches the
     * cleaned <code>locationName</code>, and each location is a map of parameters for the Json
     * response as specified: <br>
     * "lat" -> Number, The latitude of the node. <br>
     * "lon" -> Number, The longitude of the node. <br>
     * "name" -> String, The actual name of the node. <br>
     * "id" -> Number, The id of the node. <br>
     */
    public static List<Map<String, Object>> getLocations(String locationName) {
        return new LinkedList<>();
    }
}
