/**
 * Created by Alex on 4/13/2016.
 */
import java.io.File;
import java.util.ArrayList;

public class QuadTree {
    private QTreeNode root;

    public class QTreeNode implements Comparable<QTreeNode> {
        private String fileName;
        private QTreeNode child1;
        private QTreeNode child2;
        private QTreeNode child3;
        private QTreeNode child4;
        private double ullat;
        private double ullon;
        private double lrlat;
        private double lrlon;
        private int depth;

        public QTreeNode(String fileName, QTreeNode child1, QTreeNode child2, QTreeNode child3, QTreeNode child4, double ullat, double ullon, double lrlat, double lrlon, int depth) {
            this.fileName = fileName;
            this.child1 = child1;
            this.child2 = child2;
            this.child3 = child3;
            this.child4 = child4;
            this.ullat = ullat;
            this.ullon = ullon;
            this.lrlat = lrlat;
            this.lrlon = lrlon;
            this.depth = depth;
        }
        public String getFileName() {
            return this.fileName;
        }
        public double getUllat() { return this.ullat; }
        public double getUllon() { return this.ullon; }
        public double getLrlat() { return this.lrlat; }
        public double getLrlon() { return this.lrlon; }
        public boolean satisfiesDepth(int depth) {
            return (this.depth == depth);
        }


        public boolean intersectsTile(double query_ullat, double query_lrlat, double query_ullon, double query_lrlon) {
            return !(query_ullon > lrlon ||
                    query_lrlon < ullon ||
                    query_ullat < lrlat ||
                    query_lrlat > ullat);
        }

        @Override
        public int compareTo(QTreeNode n) {
            if (this.ullat > n.ullat) {
                return -1;
            } else if (this.ullat < n.ullat) {
                return 1;
            } else if (this.ullat == n.ullat && this.ullon < n.ullon) {
                return -1;
            } else if (this.ullat == n.ullat && this.ullon > n.ullon) {
                return 1;
            } else {
                return 1;
            }
        }
    }

    public QuadTree() {
        root = new QTreeNode("root", null , null, null, null, MapServer.ROOT_ULLAT, MapServer.ROOT_ULLON, MapServer.ROOT_LRLAT, MapServer.ROOT_LRLON, 0);
        completeQTree(root, "");

    }

    public QTreeNode root() {
        return root;
    }

    public void completeQTree(QTreeNode n, String s) {
        String s1 = s + "1";
        String s2 = s + "2";
        String s3 = s + "3";
        String s4 = s + "4";
        File s1f = new File("img/" + s1 + ".png");
        if (!s1f.exists()) {
            return;
        } else {
            n.child1 = new QTreeNode(s1, null, null, null, null, n.ullat, n.ullon, (n.ullat + n.lrlat)/2, (n.ullon + n.lrlon)/2, n.depth+1);
            n.child2 = new QTreeNode(s2, null, null, null, null, n.ullat, (n.ullon + n.lrlon)/2, (n.lrlat+n.ullat)/2, n.lrlon, n.depth+1);
            n.child3 = new QTreeNode(s3, null, null, null, null, (n.ullat + n.lrlat)/2, n.ullon, n.lrlat, (n.ullon + n.lrlon)/2, n.depth+1);
            n.child4 = new QTreeNode(s4, null, null, null, null, (n.ullat + n.lrlat)/2, (n.ullon + n.lrlon)/2, n.lrlat, n.lrlon, n.depth+1);
            completeQTree(n.child1, s1);
            completeQTree(n.child2, s2);
            completeQTree(n.child3, s3);
            completeQTree(n.child4, s4);
        }
    }

    public void traverseDepth(QTreeNode n, int depth, double ullat, double lrlat, double ullon, double lrlon, ArrayList tiles) {
        if (n.satisfiesDepth(depth) && n.intersectsTile(ullat, lrlat, ullon, lrlon)) {
            tiles.add(n);
        } else {
            if (n.child1.intersectsTile(ullat, lrlat, ullon, lrlon)) {
                traverseDepth(n.child1, depth, ullat, lrlat, ullon, lrlon, tiles);
            }
            if (n.child2.intersectsTile(ullat, lrlat, ullon, lrlon)) {
                traverseDepth(n.child2, depth, ullat, lrlat, ullon, lrlon, tiles);
            }
            if (n.child3.intersectsTile(ullat, lrlat, ullon, lrlon)) {
                traverseDepth(n.child3, depth, ullat, lrlat, ullon, lrlon, tiles);
            }
            if (n.child4.intersectsTile(ullat, lrlat, ullon, lrlon)) {
                traverseDepth(n.child4, depth, ullat, lrlat, ullon, lrlon, tiles);
            }
        }
    }




}
