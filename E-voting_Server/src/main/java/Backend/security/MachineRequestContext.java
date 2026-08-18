package Backend.security;

import Backend.model.Machine;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Carries the authenticated terminal from the filter to the controllers.
 *
 * <p>Controllers previously re-checked a shared token header themselves, in every
 * handler, with no notion of <em>which</em> terminal was calling. Authentication now
 * happens once in {@link MachineAuthenticationFilter} and the identified machine is
 * read from here, so every recorded vote can be attributed to a specific booth.
 */
public final class MachineRequestContext {

    private static final String ATTRIBUTE = MachineRequestContext.class.getName() + ".machine";

    private MachineRequestContext() {}

    static void set(HttpServletRequest request, Machine machine) {
        request.setAttribute(ATTRIBUTE, machine);
    }

    /**
     * @throws IllegalStateException if called on a request that did not pass machine
     *         authentication, which would be a routing mistake rather than a runtime
     *         condition worth handling
     */
    public static Machine require(HttpServletRequest request) {
        Machine machine = (Machine) request.getAttribute(ATTRIBUTE);
        if (machine == null) {
            throw new IllegalStateException(
                    "No authenticated machine on this request. It bypassed MachineAuthenticationFilter.");
        }
        return machine;
    }

    public static String requireMachineId(HttpServletRequest request) {
        return require(request).getMachineId();
    }
}
