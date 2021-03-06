package uk.ac.rhul.cs.cl1.ui.cytoscape;

import giny.model.Node;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.Arrays;
import java.util.List;

import javax.swing.JOptionPane;

import uk.ac.rhul.cs.cl1.ClusterONE;
import uk.ac.rhul.cs.cl1.ClusterONEAlgorithmParameters;
import uk.ac.rhul.cs.cl1.CohesivenessFunction;
import uk.ac.rhul.cs.cl1.MutableNodeSet;
import uk.ac.rhul.cs.cl1.NodeSet;
import uk.ac.rhul.cs.cl1.QualityFunction;
import uk.ac.rhul.cs.cl1.ValuedNodeSet;
import uk.ac.rhul.cs.utils.Pair;

import cytoscape.CyNetwork;
import cytoscape.Cytoscape;
import cytoscape.data.CyAttributes;
import cytoscape.task.ui.JTaskConfig;
import cytoscape.task.util.TaskManager;
import cytoscape.view.CyMenus;
import cytoscape.view.CyNetworkView;
import cytoscape.view.CytoscapeDesktop;

public class CytoscapePlugin extends cytoscape.plugin.CytoscapePlugin implements PropertyChangeListener {
	/**
	 * Attribute name used by ClusterONE to store status information for each node.
	 * 
	 * A node can have one and only one of the following status values:
	 * 
	 * <ul>
	 * <li>0 = the node is an outlier (it is not included in any cluster)</li>
	 * <li>1 = the node is included in only a single cluster</li>
	 * <li>2 = the node is an overlap (it is included in more than one cluster)</li>
	 * </ul>
	 */
	public static final String ATTRIBUTE_STATUS = "cl1.Status";
	
	/**
	 * Attribute name used by ClusterONE to store affinities of vertices to a
	 * given cluster.
	 */
	public static final String ATTRIBUTE_AFFINITY = "cl1.Affinity";
	
	/**
	 * Local cache for converted ClusterONE representations of Cytoscape networks
	 */
	private static CyNetworkCache networkCache = new CyNetworkCache();
	
	/**
	 * Action class responding for popup menu invocations in network views
	 */
	private NodeContextMenuAction nodeContextMenuAction = new NodeContextMenuAction();
	
	public CytoscapePlugin() {
		/* Set up menus */
		CyMenus cyMenus = Cytoscape.getDesktop().getCyMenus();
		cyMenus.addAction(new ShowControlPanelAction());
		cyMenus.addAction(GrowClusterAction.getGlobalInstance());
		cyMenus.addAction(AffinityColouringAction.getGlobalInstance());
		cyMenus.addAction(new HelpAction("introduction"));
		cyMenus.addAction(new AboutAction());
		
		/* Disable actions depending on the control panel */
		GrowClusterAction.getGlobalInstance().setEnabled(false);
		AffinityColouringAction.getGlobalInstance().setEnabled(false);
		
		/* Set up the attributes that will be used by ClusterONE */
		CyAttributes nodeAttributes = Cytoscape.getNodeAttributes();
		nodeAttributes.setAttributeDescription(ATTRIBUTE_STATUS,
				"This attribute is used by the ClusterONE plugin to indicate the status "+
				"of a node after a ClusterONE run. The status codes are as follows:\n\n"+
				"Outlier = the node is not part of any cluster (i.e. it is an outlier)\n"+
				"Cluster = the node is part of exactly one cluster\n"+
				"Overlap = the node is part of multiple clusters (i.e. it is an overlap)"
		);
		nodeAttributes.setAttributeDescription(ATTRIBUTE_AFFINITY,
				"This attribute is used by the ClusterONE plugin to indicate the "+
				"affinity of a node to a given cluster. The attribute values can be "+
				"(re)calculated manually by right-clicking on a cluster in the "+
				"ClusterONE result table and selecting the appropriate menu item."
		);
		
		/* Register ourselves as a listener for newly created networks and network view
		 * focus events */
		Cytoscape.getDesktop().getSwingPropertyChangeSupport().addPropertyChangeListener(
				CytoscapeDesktop.NETWORK_VIEW_CREATED, this
		);
		
		/* Register ourselves as a listener for network changes */
		Cytoscape.getPropertyChangeSupport().addPropertyChangeListener(this);
	}
	
	/**
	 * Converts a {@link CyNetwork} to a {@link Graph} using the global {@link CyNetworkCache}
	 * 
	 * @param  network     the network being converted
	 * @param  weightAttr  the attribute name used for the weights
	 * @return the converted graph or null if there was an error
	 */
	public static Graph convertCyNetworkToGraph(CyNetwork network, String weightAttr) {
		Graph graph = null;
		
		try {
			graph = networkCache.convertCyNetworkToGraph(network, weightAttr);
		} catch (NonNumericAttributeException ex) {
			JOptionPane.showMessageDialog(Cytoscape.getDesktop(),
					"Weight attribute values must be numeric.",
					"Error - invalid weight attribute",
					JOptionPane.ERROR_MESSAGE);
			return null;
		}
		
		return graph;
	}
	
	/**
	 * Returns a reference to the network cache held by the plugin
	 */
	public static CyNetworkCache getNetworkCache() {
		return networkCache;
	}
	
	/**
	 * Runs ClusterONE with the given parameters on the given Cytoscape network
	 * 
	 * @param network        the network we are running the algorithm on
	 * @param parameters     the algorithm parameters of ClusterONE
	 * @param weightAttr     edge attribute holding edge weights
	 * @param setAttributes  whether to set ClusterONE related node/edge attributes on the
	 *                       network in the end
	 */
	protected static Pair<List<ValuedNodeSet>, List<Node>> runAlgorithm(CyNetwork network,
			ClusterONEAlgorithmParameters parameters, String weightAttr,
			boolean setAttributes) {
		networkCache.invalidate(network);
		Graph graph = convertCyNetworkToGraph(network, weightAttr);
		
		if (graph == null)
			return null;
		
		List<ValuedNodeSet> clusters = runAlgorithm(graph, parameters, weightAttr);
		
		if (clusters != null && setAttributes)
			setStatusAttributesOnGraph(graph, clusters);
		
		return Pair.create(clusters, graph.getNodeMapping());
	}
	
	/**
	 * Runs ClusterONE with the given parameters on the given graph
	 * 
	 * @param graph          the graph we are running the algorithm on
	 * @param parameters     the algorithm parameters of ClusterONE
	 * @param weightAttr     edge attribute holding edge weights
	 */
	protected static List<ValuedNodeSet> runAlgorithm(Graph graph,
			ClusterONEAlgorithmParameters parameters, String weightAttr) {
		if (graph.getEdgeCount() == 0) {
			JOptionPane.showMessageDialog(Cytoscape.getDesktop(),
					"The selected network contains no edges",
					"Error - no edges in network",
					JOptionPane.ERROR_MESSAGE);
			return null;
		}
		
		JTaskConfig config = new JTaskConfig();
		config.displayCancelButton(true);
		config.displayStatus(true);
		
		ClusterONECytoscapeTask task = new ClusterONECytoscapeTask(parameters);
		task.setGraph(graph);
		TaskManager.executeTask(task, config);
		
		return task.getResults();
	}
	
	/**
	 * Sets some ClusterONE specific node status attributes on a CyNetwork that
	 * will be used by VizMapper later.
	 * 
	 * @param graph      the ClusterONE graph representation
	 * @param results    results of the analysis
	 */
	private static void setStatusAttributesOnGraph(Graph graph, List<ValuedNodeSet> results) {
		int[] occurrences = new int[graph.getNodeCount()];
		Arrays.fill(occurrences, 0);
		
		for (NodeSet nodeSet: results) {
			for (Integer nodeIdx: nodeSet) {
				occurrences[nodeIdx]++;
			}
		}
		
		CyAttributes nodeAttributes = Cytoscape.getNodeAttributes();
		String[] values = {"Outlier", "Cluster", "Overlap"};
		
		byte attrType = nodeAttributes.getType(ATTRIBUTE_STATUS);
		if (attrType != CyAttributes.TYPE_UNDEFINED && attrType != CyAttributes.TYPE_STRING) {
			int response = JOptionPane.showConfirmDialog(Cytoscape.getDesktop(),
					"A node attribute named "+ATTRIBUTE_STATUS+" already exists and "+
					"it is not a string attribute.\nDo you want to remove the existing "+
					"attribute and re-register it as a string attribute?",
					"Attribute type mismatch",
					JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (response == JOptionPane.NO_OPTION)
				return;
			
			nodeAttributes.deleteAttribute(ATTRIBUTE_STATUS);
		}
		
		int i = 0;
		for (Node node: graph.getNodeMapping()) {
			if (occurrences[i] > 2)
				occurrences[i] = 2;
			nodeAttributes.setAttribute(node.getIdentifier(), ATTRIBUTE_STATUS,
					values[occurrences[i]]);
			i++;
		}
	}
	
	/**
	 * Sets the ClusterONE specific node affinity attributes on a CyNetwork that
	 * will be used by VizMapper later.
	 * 
	 * @param graph    the ClusterONE graph representation
	 * @param nodes    the list of the selected node indices
	 */
	public static void setAffinityAttributesOnGraph(Graph graph, List<Integer> nodes) {
		CyAttributes nodeAttributes = Cytoscape.getNodeAttributes();
		
		byte attrType = nodeAttributes.getType(ATTRIBUTE_AFFINITY);
		if (attrType != CyAttributes.TYPE_UNDEFINED && attrType != CyAttributes.TYPE_FLOATING) {
			int response = JOptionPane.showConfirmDialog(Cytoscape.getDesktop(),
					"A node attribute named "+ATTRIBUTE_AFFINITY+" already exists and "+
					"it is not a floating point attribute.\nDo you want to remove the existing "+
					"attribute and re-register it as a floating point attribute?",
					"Attribute type mismatch",
					JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (response == JOptionPane.NO_OPTION)
				return;
			
			nodeAttributes.deleteAttribute(ATTRIBUTE_AFFINITY);
		}
		
		int i = 0;
		MutableNodeSet nodeSet = new MutableNodeSet(graph, nodes);
		QualityFunction func = new CohesivenessFunction(); // TODO: fix it, it should not be hardwired
		double currentQuality = func.calculate(nodeSet);
		double affinity;
		
		for (Node node: graph.getNodeMapping()) {
			if (nodeSet.contains(i))
				/* multiplying by -1 here: we want internal nodes to have a positive
				 * affinity if they "should" belong to the cluster
				 */
				affinity = - (func.getRemovalAffinity(nodeSet, i) - currentQuality);
			else
				affinity = func.getAdditionAffinity(nodeSet, i) - currentQuality;
			
			if (Double.isNaN(affinity))
				affinity = 0.0;

			nodeAttributes.setAttribute(node.getIdentifier(), ATTRIBUTE_AFFINITY, affinity);
			i++;
		}
		
		/* Set the appropriate ClusterONE visual style */
		VisualStyleManager.ensureVizMapperStylesRegistered(false);
		VisualStyleManager.updateAffinityStyleRange();
		Cytoscape.getVisualMappingManager().setVisualStyle(VisualStyleManager.VISUAL_STYLE_BY_AFFINITY);
		Cytoscape.getVisualMappingManager().applyAppearances();
		Cytoscape.getCurrentNetworkView().redrawGraph(false, true);
	}
	
	/**
	 * Method triggered when a new network is created
	 */
	public void propertyChange(PropertyChangeEvent e) {
		String property = e.getPropertyName();
		
		if (property == null)
			return;
		
		if (CytoscapeDesktop.NETWORK_VIEW_CREATED.equals(property)) {
			/* Register the appropriate node context menu for newly created networks */
			CyNetworkView view = (CyNetworkView)e.getNewValue();
			view.addNodeContextMenuListener(nodeContextMenuAction);
		} else if (Cytoscape.NETWORK_MODIFIED.equals(property)) {
			/* If a network was modified, remove it from the network cache */
			networkCache.invalidate(Cytoscape.getCurrentNetwork());
		} else if (Cytoscape.NETWORK_DESTROYED.equals(property)) {
			/* If a network was destroyed, remove it from the network cache */
			networkCache.invalidate(Cytoscape.getCurrentNetwork());
		}
	}

	/**
	 * Shows a message dialog box that informs the user about a possible bug in ClusterONE.
	 * 
	 * @param  message   the message to be shown
	 */
	public static void showBugMessage(String message) {
		StringBuilder sb = new StringBuilder(message);
		sb.append("\n\n");
		sb.append("This is possibly a bug in ");
		sb.append(ClusterONE.applicationName);
		sb.append(".\nPlease inform the developers about what you were doing and\n");
		sb.append("what the expected result would have been.");
		
		JOptionPane.showMessageDialog(Cytoscape.getDesktop(),
				sb.toString(), "Possible bug in "+ClusterONE.applicationName,
				JOptionPane.ERROR_MESSAGE);
	}
	
	/**
	 * Shows an error message in a dialog box
	 * 
	 * @param  message  the error message to be shown
	 */
	public static void showErrorMessage(String message) {
		JOptionPane.showMessageDialog(Cytoscape.getDesktop(), message,
				ClusterONE.applicationName, JOptionPane.ERROR_MESSAGE);
	}

	/**
	 * Shows a message dialog box that informs the user about something
	 * 
	 * @param  message  the message to be shown
	 */
	public static void showInformationMessage(String message) {
		JOptionPane.showMessageDialog(Cytoscape.getDesktop(), message,
				ClusterONE.applicationName, JOptionPane.INFORMATION_MESSAGE);
	}
}
