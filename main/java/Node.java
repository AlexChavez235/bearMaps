/**
 * Created by Alex on 4/18/2016.
 */
import java.util.HashSet;
import java.util.Set;

public class Node implements Comparable<Node> {
    Long id;
    Double lat, lon;
    Set<Connections> connectionsSet;
    double fn;

    public Node(Long id, Double lat, Double lon) {
        this.id = id;
        this.lat = lat;
        this.lon = lon;
        connectionsSet = new HashSet<>();
    }

    public void setFn(double fn) {
        this.fn = fn;
    }

    public int compareTo(Node n) {
        if (this.fn < n.fn) {
            return -1;
        } else {
            return 1;
        }
    }

    @Override
    public String toString() {
        return "Node{" +
                "id=" + id +
                ", lat=" + lat +
                ", lon=" + lon +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Node node = (Node) o;

        if (id != null ? !id.equals(node.id) : node.id != null) return false;
        if (lat != null ? !lat.equals(node.lat) : node.lat != null) return false;
        if (lon != null ? !lon.equals(node.lon) : node.lon != null) return false;
        return connectionsSet != null ? connectionsSet.equals(node.connectionsSet) : node.connectionsSet == null;

    }

    @Override
    public int hashCode() {
        int result = id != null ? id.hashCode() : 0;
        result = 31 * result + (lat != null ? lat.hashCode() : 0);
        result = 31 * result + (lon != null ? lon.hashCode() : 0);
        result = 31 * result + (connectionsSet != null ? connectionsSet.hashCode() : 0);
        return result;
    }
}
