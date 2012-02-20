package rinde.sim.serializers;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map.Entry;

import rinde.sim.core.graph.Connection;
import rinde.sim.core.graph.EdgeData;
import rinde.sim.core.graph.Graph;
import rinde.sim.core.graph.LengthEdgeData;
import rinde.sim.core.graph.MultimapGraph;
import rinde.sim.core.graph.Point;
import rinde.sim.core.graph.TableGraph;

/**
 * Dot format serializer for a road model graph.
 * Allows for reading storing maps in dot format.
 * @author Bartosz Michalik <bartosz.michalik@cs.kuleuven.be>
 *
 */
public class DotGraphSerializer extends AbstractGraphSerializer<LengthEdgeData> {

	private SerializerFilter<? extends Object>[] filters;

	public DotGraphSerializer(SerializerFilter<?>... filters) {
		this.filters = filters;
		if(filters == null) filters = new SerializerFilter<?>[0];
	}
	
	public DotGraphSerializer() {
		this(new SerializerFilter[0]);
	}
	
	@Override
	public Graph<LengthEdgeData> read(Reader r) throws IOException {
		
		BufferedReader reader = new BufferedReader(r);

		TableGraph<LengthEdgeData> graph = new TableGraph<LengthEdgeData>(LengthEdgeData.EMPTY);

		HashMap<String, Point> nodeMapping = new HashMap<String, Point>();
		String line;
		while ((line = reader.readLine()) != null) {
			if (line.contains("pos=")) {
				String nodeName = line.substring(0, line.indexOf("[")).trim();
				String[] position = line.split("\"")[1].split(",");
				Point p = new Point(Double.parseDouble(position[0]),
						Double.parseDouble(position[1]));
				nodeMapping.put(nodeName, p);
			} else if (line.contains("->")) {
				// example:
				// node1004 -> node820[label="163.3"]
				String[] names = line.split("->");
				String fromStr = names[0].trim();
				String toStr = names[1].substring(0, names[1].indexOf("["))
						.trim();
				double distance = Double.parseDouble(line.split("\"")[1]);
				Point from = nodeMapping.get(fromStr);
				Point to = nodeMapping.get(toStr);
				
				for (SerializerFilter<?> f : filters) {
					if(f.filterOut(from, to)) continue;
				}
				
				if (Point.distance(from, to) == distance) {
					graph.addConnection(from, to);
				} else {
					graph.addConnection(from, to, new LengthEdgeData(distance));
				}
			}
		}
		Graph<LengthEdgeData> g = new MultimapGraph<LengthEdgeData>();
		g.merge(graph);
		return g;

	}

	@Override
	public void write(Graph<? extends LengthEdgeData> graph, Writer writer) throws IOException {
		final BufferedWriter out = new BufferedWriter(writer);

		final StringBuilder string = new StringBuilder();
		string.append("digraph genegraph {\n");

		int nodeId = 0;
		HashMap<Point, Integer> idMap = new HashMap<Point, Integer>();
		for (Point p : graph.getNodes()) {
			string.append("node" + nodeId + "[pos=\"" + p.x / 3 + "," + p.y / 3 + "\", label=\"" + p + "\", pin=true]\n");
			idMap.put(p, nodeId);
			nodeId++;
		}

		for (Connection<? extends LengthEdgeData> entry : graph.getConnections()) {

			String label = "" + Math.round(graph.connectionLength(entry.from, entry.to) * 10d) / 10d;
			if (!idMap.containsKey(entry.to)) {
				Point p = entry.to;
				string.append("node" + nodeId + "[pos=\"" + p.x / 3 + "," + p.y / 3 + "\", label=\"" + p + "\", pin=true]\n");
				idMap.put(p, nodeId);
				nodeId++;
			}
			string.append("node" + idMap.get(entry.from) + " -> node" + idMap.get(entry.to) + "[label=\"" + label + "\"]\n");
		}
		string.append("}");
	}
}
