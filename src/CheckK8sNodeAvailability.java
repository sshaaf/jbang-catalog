///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS io.fabric8:kubernetes-client:6.10.0
//DEPS info.picocli:picocli:4.7.6
//MAIN CheckK8sNodeAvailability

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
// Removed unused import: import io.fabric8.kubernetes.client.dsl.PodResource;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * JBang script to check Kubernetes node availability for a pending pod
 * based on taints/tolerations and memory requests vs. allocatable resources.
 */
@Command(name = "CheckK8sNodeAvailability",
         mixinStandardHelpOptions = true,
         version = "CheckK8sNodeAvailability 1.4", // Increment version
         description = "Checks Kubernetes node availability for a pending pod based on taints and memory requests.")
public class CheckK8sNodeAvailability implements Callable<Integer> {

    @Parameters(index = "0", description = "Name of the pending pod.")
    private String podName;

    @Parameters(index = "1", description = "Namespace of the pending pod.")
    private String namespace;

    // --- Constants for Memory Conversion ---
    // Using BigDecimal for precision, base unit Ki
    private static final BigDecimal KIB_IN_BYTES = new BigDecimal("1024");
    private static final BigDecimal MIB_IN_KIB = new BigDecimal("1024");
    private static final BigDecimal GIB_IN_KIB = MIB_IN_KIB.multiply(new BigDecimal("1024"));
    private static final BigDecimal TIB_IN_KIB = GIB_IN_KIB.multiply(new BigDecimal("1024"));
    private static final BigDecimal PIB_IN_KIB = TIB_IN_KIB.multiply(new BigDecimal("1024"));
    private static final BigDecimal EIB_IN_KIB = PIB_IN_KIB.multiply(new BigDecimal("1024"));

    // Regex to extract number and optional unit from memory string
    private static final Pattern MEMORY_PATTERN = Pattern.compile("^(\\d+(\\.\\d+)?)\\s*([A-Za-z]*)$");


    // --- Helper Function: Parse Memory Units ---
    /**
     * Parses Kubernetes memory strings (e.g., '1Gi', '500Mi', '1024Ki')
     * into Kibibytes (KiB) using Regex.
     * Returns a BigDecimal representing KiB or BigDecimal.ZERO if parsing fails.
     */
    public static BigDecimal parseMemoryToKib(String memStr) {
        if (memStr == null || memStr.isEmpty()) {
            return BigDecimal.ZERO;
        }
        Matcher matcher = MEMORY_PATTERN.matcher(memStr.trim());
        if (!matcher.matches()) {
            System.err.printf("Warning: Could not parse memory string format '%s'. Treating as 0 KiB%n", memStr);
            return BigDecimal.ZERO;
        }

        try {
            BigDecimal amount = new BigDecimal(matcher.group(1));
            String unit = matcher.group(3); // Unit part (e.g., "Ki", "Mi", "G", "")

            // Normalize unit to expected format (e.g., handle case insensitivity if needed)
            unit = (unit == null) ? "" : unit; // Ensure unit is not null

            switch (unit) {
                case "Ki": return amount;
                case "Mi": return amount.multiply(MIB_IN_KIB);
                case "Gi": return amount.multiply(GIB_IN_KIB);
                case "Ti": return amount.multiply(TIB_IN_KIB);
                case "Pi": return amount.multiply(PIB_IN_KIB);
                case "Ei": return amount.multiply(EIB_IN_KIB);
                // Handle SI units
                case "k": return amount.multiply(new BigDecimal("1000")).divide(KIB_IN_BYTES, 0, RoundingMode.DOWN);
                case "M": return amount.multiply(new BigDecimal("1000000")).divide(KIB_IN_BYTES, 0, RoundingMode.DOWN);
                case "G": return amount.multiply(new BigDecimal("1000000000")).divide(KIB_IN_BYTES, 0, RoundingMode.DOWN);
                // Handle base unit (bytes) - assume bytes if no unit
                case "":
                    // Check if the original string was just a number (implying bytes)
                    // Note: K8s usually provides units, but handle this defensively.
                    return amount.divide(KIB_IN_BYTES, 0, RoundingMode.DOWN);
                default:
                    System.err.printf("Warning: Unrecognized memory unit '%s' in '%s'. Assuming bytes and converting to KiB.%n", unit, memStr);
                    return amount.divide(KIB_IN_BYTES, 0, RoundingMode.DOWN); // Treat unrecognized as bytes
            }
        } catch (NumberFormatException e) {
             System.err.printf("Warning: Could not parse numeric value in memory string '%s'. Treating as 0 KiB%n", memStr);
             return BigDecimal.ZERO;
        } catch (Exception e) { // Catch other unexpected errors
             System.err.printf("Warning: Unexpected error parsing memory string '%s': %s. Treating as 0 KiB%n", memStr, e.getMessage());
             return BigDecimal.ZERO;
        }
    }

    // --- Helper Function: Check Toleration ---
    /**
     * Checks if a given taint is tolerated by the pod's tolerations list.
     * Returns true if tolerated, false otherwise.
     */
    public static boolean checkToleration(Taint taint, List<Toleration> podTolerations) {
        if (podTolerations == null) {
            return false;
        }
        for (Toleration toleration : podTolerations) {
            // Check key match (toleration key is null means wildcard)
            boolean keyMatch = toleration.getKey() == null || toleration.getKey().equals(taint.getKey());

            // Check effect match (toleration effect is null means wildcard for effect)
            boolean effectMatch = toleration.getEffect() == null || toleration.getEffect().equals(taint.getEffect());

            // Check operator
            boolean operatorMatch = false;
            String operator = toleration.getOperator(); // Can be null, defaults to "Equal"

            if ("Exists".equals(operator)) {
                // 'Exists' operator only requires key and effect match (value is ignored)
                // Key must exist on the taint and match toleration key if specified.
                operatorMatch = taint.getKey() != null && (toleration.getKey() == null || toleration.getKey().equals(taint.getKey()));
            } else { // Operator is "Equal" (explicitly or by default)
                // Check if values match (both null or equal)
                operatorMatch = (toleration.getValue() == null && taint.getValue() == null) ||
                                (toleration.getValue() != null && toleration.getValue().equals(taint.getValue()));
            }

            // If all conditions match for this toleration, the taint is tolerated
            if (keyMatch && effectMatch && operatorMatch) {
                // Check tolerationSeconds if applicable (NoExecute effect)
                if ("NoExecute".equals(taint.getEffect()) && toleration.getTolerationSeconds() != null) {
                    // Basic check assumes toleration is valid if present.
                }
                return true; // Found a matching toleration
            }
        }
        return false; // No toleration matched
    }


    @Override
    public Integer call() { // Main logic moved into call() for Picocli
        System.out.printf("Checking scheduling prerequisites for pod '%s' in namespace '%s'...%n%n", podName, namespace);

        // Use try-with-resources for the Kubernetes client
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {

            // --- 1. Get Pending Pod Details ---
            Pod pod = client.pods().inNamespace(namespace).withName(podName).get();
            if (pod == null) {
                System.err.printf("Error: Pod '%s' not found in namespace '%s'.%n", podName, namespace);
                return 1; // Indicate error
            }

            // Calculate the largest memory request among containers
            BigDecimal podMemoryRequestKib = BigDecimal.ZERO;
            List<Container> containers = Optional.ofNullable(pod.getSpec().getContainers()).orElse(List.of()); // Handle null containers list
            if (!containers.isEmpty()) {
                System.out.println("Pod Memory Requests per Container:");
                for (Container container : containers) {
                    String memReqStr = "N/A";
                    BigDecimal reqKib = BigDecimal.ZERO;
                    ResourceRequirements resources = container.getResources();
                    // Check requests map exists and contains memory key
                    if (resources != null && resources.getRequests() != null && resources.getRequests().containsKey("memory")) {
                        Quantity memQuantity = resources.getRequests().get("memory");
                        if (memQuantity != null) { // Ensure Quantity object is not null
                           // Get the string representation (e.g., "512Mi") to parse manually
                           memReqStr = memQuantity.toString();
                           reqKib = parseMemoryToKib(memReqStr); // Use our regex helper
                           if (reqKib.compareTo(podMemoryRequestKib) > 0) {
                               podMemoryRequestKib = reqKib; // Keep track of the largest request
                           }
                        } else {
                             memReqStr = "<null quantity>"; // Indicate if the map entry was null
                        }
                    }
                    System.out.printf("  - Container '%s': %s (%s KiB)%n", container.getName(), memReqStr, reqKib.toBigInteger());
                }
            }

            if (podMemoryRequestKib.compareTo(BigDecimal.ZERO) == 0) {
                System.out.printf("%nWarning: Pod '%s' has no memory requests defined. Skipping memory checks.%n", podName);
            } else {
                System.out.printf("%nPod '%s' requires %s KiB of memory (largest container request).%n", podName, podMemoryRequestKib.toBigInteger());
            }

            // Get Pod Tolerations
            List<Toleration> podTolerations = Optional.ofNullable(pod.getSpec().getTolerations()).orElse(new ArrayList<>());
            System.out.printf("Pod Tolerations: %s%n", podTolerations.isEmpty() ? "None" : podTolerations.stream().map(Object::toString).collect(Collectors.joining(", ")));


            // --- 2. Get All Nodes ---
            NodeList nodeList = client.nodes().list();
            List<Node> nodes = Optional.ofNullable(nodeList.getItems()).orElse(List.of()); // Handle null items list
            int totalNodes = nodes.size();
            System.out.printf("%nFound %d nodes in the cluster.%n", totalNodes);

            // --- 3. Check Each Node ---
            int availableNodesCount = 0;
            List<String> rejectedByTaint = new ArrayList<>();
            List<String> rejectedByMemory = new ArrayList<>();
            List<String> nodesWithIssues = new ArrayList<>(); // For nodes where checks couldn't complete

            System.out.println("\n--- Node Analysis ---");
            for (Node node : nodes) {
                String nodeName = node.getMetadata().getName();
                System.out.printf("%nAnalyzing Node: %s%n", nodeName);
                boolean isRejectedByTaint = false;
                boolean isRejectedByMemory = false;
                boolean checkIncomplete = false; // Flag if checks couldn't finish for this node

                // a) Check Taints
                List<Taint> nodeTaints = Optional.ofNullable(node.getSpec().getTaints()).orElse(new ArrayList<>());
                if (!nodeTaints.isEmpty()) {
                     System.out.printf("  Taints: %s%n", nodeTaints.stream()
                                                                 .map(t -> String.format("%s=%s:%s", t.getKey(), t.getValue(), t.getEffect()))
                                                                 .collect(Collectors.joining(", ")));
                    for (Taint taint : nodeTaints) {
                        // Only consider taints that prevent scheduling by default
                        if ("NoSchedule".equals(taint.getEffect()) || "NoExecute".equals(taint.getEffect())) {
                            if (!checkToleration(taint, podTolerations)) {
                                String reason = String.format("Untolerated taint '%s=%s:%s'", taint.getKey(), taint.getValue(), taint.getEffect());
                                rejectedByTaint.add(String.format("%s (%s)", nodeName, reason));
                                isRejectedByTaint = true;
                                break; // One untolerated taint is enough
                            }
                        }
                    }
                } else {
                    System.out.println("  Taints: None");
                }

                if (isRejectedByTaint) {
                    System.out.println("  Result: Rejected due to taint.");
                    continue; // Move to the next node
                }

                // b) Check Memory (only if pod requests memory and not rejected by taint)
                if (podMemoryRequestKib.compareTo(BigDecimal.ZERO) > 0) {
                    // Get Node Allocatable Memory
                    String allocatableMemoryStr = Optional.ofNullable(node.getStatus())
                                                          .map(NodeStatus::getAllocatable)
                                                          .map(m -> m.get("memory"))
                                                          .map(Quantity::toString) // Get string like "15222852Ki"
                                                          .orElse("0");
                    BigDecimal allocatableMemoryKib = parseMemoryToKib(allocatableMemoryStr); // Use our regex helper
                    System.out.printf("  Allocatable Memory: %s (%s KiB)%n", allocatableMemoryStr, allocatableMemoryKib.toBigInteger());

                    // Calculate Allocated Memory Requests on the node
                    BigDecimal allocatedMemoryRequestKib = BigDecimal.ZERO;
                    try {
                        // List pods running on this specific node (excluding completed/failed)
                        // *** Use ListOptions instead of withFieldSelector ***
                        String fieldSelector = String.format("spec.nodeName=%s,status.phase!=Failed,status.phase!=Succeeded", nodeName);
                        ListOptions listOptions = new ListOptionsBuilder()
                                .withFieldSelector(fieldSelector)
                                .build();
                        PodList nodePods = client.pods().inAnyNamespace().list(listOptions); // Pass options to list()

                        for (Pod npod : Optional.ofNullable(nodePods.getItems()).orElse(List.of())) { // Handle null items
                            if (npod.getSpec().getContainers() != null) {
                                for (Container container : npod.getSpec().getContainers()) {
                                    if (container.getResources() != null && container.getResources().getRequests() != null) {
                                        Quantity memReq = container.getResources().getRequests().get("memory");
                                        if (memReq != null) {
                                            // Parse the request string manually
                                            allocatedMemoryRequestKib = allocatedMemoryRequestKib.add(parseMemoryToKib(memReq.toString()));
                                        }
                                    }
                                }
                            }
                        }
                        System.out.printf("  Requested by Existing Pods: %s KiB%n", allocatedMemoryRequestKib.toBigInteger());

                    } catch (KubernetesClientException e) {
                        String reason = String.format("API Error listing pods for memory calculation: %s", e.getMessage());
                        System.err.printf("  Warning: %s%n", reason);
                        nodesWithIssues.add(String.format("%s (%s)", nodeName, reason));
                        checkIncomplete = true; // Mark check as incomplete for this node
                    } catch (Exception e) { // Catch potential errors during pod processing
                         String reason = String.format("Error processing pods on node: %s", e.getMessage());
                         System.err.printf("  Warning: %s%n", reason);
                         nodesWithIssues.add(String.format("%s (%s)", nodeName, reason));
                         checkIncomplete = true;
                    }


                    // Only proceed with memory comparison if calculation was successful
                    if (!checkIncomplete) {
                        BigDecimal availableMemoryKib = allocatableMemoryKib.subtract(allocatedMemoryRequestKib);
                        System.out.printf("  Available Memory (Allocatable - Requested): %s KiB%n", availableMemoryKib.toBigInteger());

                        // Compare available memory with the pod's request
                        if (availableMemoryKib.compareTo(podMemoryRequestKib) < 0) {
                            String reason = String.format("Insufficient memory (Needs %s KiB, Available %s KiB)",
                                                          podMemoryRequestKib.toBigInteger(), availableMemoryKib.toBigInteger());
                            rejectedByMemory.add(String.format("%s (%s)", nodeName, reason));
                            isRejectedByMemory = true;
                            System.out.println("  Result: Rejected due to memory.");
                        }
                    } else {
                         System.out.println("  Result: Check Incomplete (due to pod listing error).");
                    }

                } // End memory check block

                // c) Final Node Status
                if (!isRejectedByTaint && !isRejectedByMemory && !checkIncomplete) {
                    availableNodesCount++;
                    System.out.println("  Result: Potentially Available.");
                } else if (checkIncomplete && !isRejectedByTaint) {
                    // If check was incomplete but not rejected by taint, don't count as available
                     System.out.println("  Result: Check Incomplete.");
                }


            } // End node loop

            // --- 4. Summary ---
            System.out.println("\n--- Scheduling Check Summary ---");
            System.out.printf("Pod: %s/%s%n", namespace, podName);
            System.out.printf("Memory Required (Largest Container): %s KiB%n", podMemoryRequestKib.toBigInteger());
            System.out.println("-".repeat(30));
            System.out.printf("Total Nodes Checked: %d%n", totalNodes);
            System.out.printf("Nodes Rejected (Untolerated Taint): %d%n", rejectedByTaint.size());
            rejectedByTaint.forEach(s -> System.out.println("  - " + s));
            System.out.printf("Nodes Rejected (Insufficient Memory): %d%n", rejectedByMemory.size());
            rejectedByMemory.forEach(s -> System.out.println("  - " + s));
            if (!nodesWithIssues.isEmpty()) {
                System.out.printf("Nodes With Check Issues (e.g., API errors): %d%n", nodesWithIssues.size());
                nodesWithIssues.forEach(s -> System.out.println("  - " + s));
            }
            System.out.printf("%nPotentially Available Nodes (passed these checks): %d%n", availableNodesCount);
            System.out.println("\nDisclaimer: This script checks only taints/tolerations and memory requests.");
            System.out.println("Actual scheduling depends on other factors like node selectors, affinity, CPU, volumes, etc.");


        } catch (KubernetesClientException e) {
            System.err.println("\nError: Kubernetes API interaction failed.");
            System.err.println("  Reason: " + e.getMessage());
            // e.printStackTrace(); // Uncomment for full stack trace if needed
            return 1; // Indicate error
        } catch (Exception e) {
            System.err.println("\nAn unexpected error occurred: " + e.getMessage());
            e.printStackTrace();
            return 1; // Indicate error
        }

        return 0; // Indicate success
    }

    // --- Main Method to run with Picocli ---
    public static void main(String... args) {
        // Ensure System.exit is called to pass the exit code back to the shell
        System.exit(new CommandLine(new CheckK8sNodeAvailability()).execute(args));
    }
}
