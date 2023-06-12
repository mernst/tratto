package star.tratto.dataset.oracles;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A JDoctor condition for a method.
 */
public class JDoctorCondition {
    @JsonProperty("operation")
    private Operation operation;
    @JsonProperty("identifiers")
    private Identifiers identifiers;
    @JsonProperty("throws")
    private List<ThrowsCondition> throwsConditions;
    @JsonProperty("post")
    private List<PostCondition> postConditions;
    @JsonProperty("pre")
    private List<PreCondition> preCondition;

    /** @return the operation (method) that this JDoctorCondition refers to */
    public Operation getOperation() {
        return this.operation;
    }

    /** @return the formal parameters of the method that this JDoctorCondition refers to */
    public Identifiers getIdentifiers() {
        return this.identifiers;
    }

    /** @return the exceptional exit conditions produced by JDoctor */
    public List<ThrowsCondition> getThrowsConditions() {
        return this.throwsConditions;
    }

    /** @return the preconditions produced by JDoctor */
    public List<PreCondition> getPreCondition() {
        return this.preCondition;
    }

    /** @return the postconditions produced by JDoctor */
    public List<PostCondition> getPostConditions() {
        return this.postConditions;
    }

    /**
     * The operation of a JDoctor condition.
     */
    public static class Operation{
        @JsonProperty("classname")
        private String classname;
        @JsonProperty("name")
        private String name;
        @JsonProperty("parameterTypes")
        private List<String> parameterTypes;

        /** @return the qualified name of the class where the method to which the JDoctor conditions
            refers is defined */
        public String getClassName() {
            return this.classname;
        }

        /** @return the qualified name of the method to which the JDoctor conditions refers */
        public String getName() {
            return this.name;
        }

        /** @return the list of type names of the parameters of the method to which the JDoctor
              conditions refers */
        public List<String> getParameterTypes() {
            return this.parameterTypes;
        }
    }

    /**
     * The identifiers of a JDoctor condition.
     */
    public static class Identifiers{
        @JsonProperty("parameters")
        private List<String> parameters;
        @JsonProperty("receiverName")
        private String receiverName;
        @JsonProperty("returnName")
        private String returnName;

        /** @return the list of the names of the parameters of the method to which the JDoctor
            condition refers */
        public List<String> getParameters() {
            return this.parameters;
        }

        /** @return the reference name of the class where the method is defined, within the oracle
            generated by JDoctor (always *receiverObjectID*) */
        public String getReceiverName() {
            return this.receiverName;
        }

        /** @return the reference name of the return type of the method, within the oracle generated
            by JDoctor (always *methodResultID*) */
        public String getReturnName() {
            return this.returnName;
        }
    }

    /**
     * A JDoctor exceptional condition.
     */
    public static class ThrowsCondition {
        @JsonProperty("exception")
        private String exception;
        @JsonProperty("description")
        private String description;
        @JsonProperty("guard")
        private Guard guard;

        /** @return the exception captured by JDoctor */
        public String getException() {
            return this.exception;
        }

        /** @return the @throws tag description of the exception, in the corresponding Javadoc comment
            of the method to which the JDoctor condition refers */
        public String getDescription() {
            return this.description;
        }

        /** @return the guard of the JDoctor exceptional condition. It contains the oracle generated
          from the description of the exceptional condition, in the Javadoc comment, and the textual representation of
          the oracle, i.e. the exact substring of the description that indicates the condition for which the exception
          is thrown. */
        public Guard getGuard() {
            return this.guard;
        }
    }

    /**
     * A JDoctor post-condition.
     */
    public static class PostCondition {
        @JsonProperty("property")
        private Property property;
        @JsonProperty("description")
        private String description;
        @JsonProperty("guard") Guard guard;

        /** @return the property of the JDoctor post-condition. It contains the oracle and
            the textual description of the JDoctor post-condition. */
        public Property getProperty() {
            return this.property;
        }

        /** @return the textual description of the JDoctor post-condition */
        public String getDescription() {
            return this.description;
        }

        /** @return the guard of the JDoctor post-condition. It contains the condition for which
            the oracle of the post-condition must be verified. In other words, if the guard condition is true, the
            corresponding oracle must be true as well. Moreover, it contains the textual description of the guard condition.
        */
        public Guard getGuard() {
            return this.guard;
        }
    }

    /**
     * A JDoctor pre-condition.
     */
    public static class PreCondition {
        @JsonProperty("description")
        private String description;
        @JsonProperty("guard")
        private Guard guard;

        /** @return the textual description of the JDoctor pre-condition */
        public String getDescription() {
            return this.description;
        }

        /** @return the guard of the JDoctor pre-condition. It contains the condition for which the
            JDoctor pre-condition is verified. Moreover, it contains the textual description of the guard condition */
        public Guard getGuard() {
            return this.guard;
        }
    }


    /**
     * A guard of a JDoctor condition.
     */
    public static class Guard{
        @JsonProperty("condition")
        private String condition;
        @JsonProperty("description")
        private String description;

        /** @return the condition for which a JDoctor pre-condition must be verified, or a JDoctor
         *     post-condition oracle must be verified, or a JDoctor exceptional condition must be thrown
         */
        public String getCondition() {
            return this.condition;
        }

        /** @return the textual description of the guard JDoctor condition */
        public String getDescription() {
            return this.description;
        }
    }

    /**
     * A property of a JDoctor post-condition.
     */
    public static class Property {
        @JsonProperty("condition")
        private String condition;
        @JsonProperty("description")
        private String description;

        /** @return the oracle of a post-condition that must be verified if the corresponding guard
            condition is true */
        public String getCondition() {
            return this.condition;
        }

        /** @return the textual description of the JDoctor post-condition oracle */
        public String getDescription() {
            return this.description;
        }
    }
}
