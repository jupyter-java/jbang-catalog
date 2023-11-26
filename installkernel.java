
///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 11+
//DEPS info.picocli:picocli:4.6.3
//DEPS com.fasterxml.jackson.core:jackson-databind:2.12.3
//FILES ipc_proxy_kernel.py

import static java.lang.String.format;
import static java.lang.System.out;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.write;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "install-kernel", mixinStandardHelpOptions = true, version = "install-kernel 0.1",
        description = "Installs JVM based Kernels that can be run via maven artifacts using JBang", showDefaultValues=true)
class installkernel implements Callable<Integer> {

    private static final String CONNECTION_FILE_MARKER = "{connection_file}";
    private static final String LANGUAGE = "java";
    private static final String INTERRUPT_MODE = "message";

     
    private enum Kernels {
                IJAVA { 
                    String shortName() { return "IJava"; }
                    String ga() { return "com.github.waikato.thirdparty:ijava"; } 
                    String v() { return "1.3.0"; }
                    String info() { return "https://github.com/SpencerPark/IJava";}
                },
                RAPAIO { 
                    String info() { return "https://github.com/padreati/rapaio-jupyter-kernel"; }
                    String shortName() { return "Rapaio"; }
                    String ga() { return "io.github.padreati:rapaio-jupyter-kernel"; } 
                    String v() { return "1.3.0"; }
                    String javaVersion() { return "21"; }
                    List<String> modules() { return List.of("java.base", "jdk.incubator.vector"); }
                    Map<String, String> options(String compilerOptions, long timeout) {
                        return Map.of(
                                    "RJK_COMPILER_OPTIONS",compilerOptions,
                                    "RJK_INIT_SCRIPT", "",
                                    "RJK_TIMEOUT_MILLIS", ""+timeout);
                    }
                },
                GANYMEDE { 
                    String info() { return "https://github.com/allen-ball/ganymede"; }
                    String shortName() { return "Ganymede"; }
                    String ga() { return "dev.hcf.ganymede:ganymede"; } 
                    String v() { return "2.1.2.20230910"; }
                    String javaVersion() { return "11"; }
                    List<String> arguments() { return List.of(
                            //from Ganymede Install.java
                        "-f", CONNECTION_FILE_MARKER); }

                  // List<String> modules() { return List.of("java.base", "jdk.incubator.vector"); }
                },

               /** 
                *  requires jbang 0.133 to get https://github.com/jbangdev/jbang/issues/1703 that supports
                *  %{deps:gav}
                */

                KOTLIN {
                    String language() { return "kotlin"; }
                    String shortName() { return "Kotlin"; }
                    String ga() { return "org.jetbrains.kotlinx:kotlin-jupyter-kernel-shadowed"; } 
                    String v() { return "0.12.0-93"; }
                    String javaVersion() { return "11"; }
                    String mainClass() { return "org.jetbrains.kotlinx.jupyter.IkotlinKt"; }
                    /*List<String> dependencies() {
                        return List.of("org.jetbrains.kotlinx:kotlin-jupyter-lib:0.12.0-85");
                    }*/
                    List<String> arguments() { return List.of(
                        "-cp=%{deps:org.jetbrains.kotlinx:kotlin-jupyter-lib:0.12.0-85}", CONNECTION_FILE_MARKER); } 
                };
                
            String shortName() { return name().substring(0, 1).toUpperCase() + name().substring(1); }
            abstract String ga();
            String v() { return "RELEASE"; }
            String gav() { return ga() + ":" + v(); }
            String javaVersion() { return "11+"; }
            String mainClass() { return null; }
            String language() { return LANGUAGE;}
            List<String> dependencies() { return List.of(); }
            List<String> modules() { return List.of(); }
            List<String> jvmArguments() { return List.of(
                "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
                        "--illegal-access=permit"
                     //   "-Djava.awt.headless=true",
                     //   "-Djdk.disableLastUsageTracking=true"
            );}
            List<String> arguments() { return List.of(CONNECTION_FILE_MARKER); }
            Map<String, String> options(String compilerOptions, long timeout) {
                return Map.of();
            }
            String info() { return null; }
        }


        @Parameters(index = "0", defaultValue = "ijava", description = "The kernel to install. Possible values: ${COMPLETION-CANDIDATES}")
        Kernels kernel;

        @Option(names = "--verbose")
        boolean verbose;

        @Option(names = "--name")
        String name;

        String name() { 
            return name==null? kernel.language() + " (" + kernel.shortName() + "/j!)":name;
        }

        @Option(names = "--jupyter-kernel-dir", description = "The name of directory to install the kernel to. Defaults to OS specific location.")
        String jupyterKernelDir;

        @Option(names = "--kernel-dir", description = "The name of directory to install the kernel to. Defaults to jbang-<kernel>")
        String kernelDir;

        String kernelDir() { 
            return kernelDir==null?"jbang-" + kernel.name().toLowerCase():kernelDir;
        }

        @Option(names="--timeout", defaultValue = "-1", description = "Timeout in milliseconds for kernel execution")
        long timeout;

        @Option(names="--ipc", defaultValue = "false", description = "Whether to install a proxy kernel that can be used to run kernel with IPC")
        boolean useIPC;

        @Option(names="--compiler-options", defaultValue = "", description = "Compiler options to pass to the kernel")
        String compilerOptions;

        private enum OSName {
                LINUX,
                WINDOWS,
                MAC,
                SOLARIS
            }
        
            private OSName findOSName() {
                String os = System.getProperty("os.name").toLowerCase();
        
                if (os.contains("win")) {
                    return OSName.WINDOWS;
                } else if (os.contains("mac")) {
                    return OSName.MAC;
                } else if (os.contains("sunos")) {
                    return OSName.SOLARIS;
                } else if (os.contains("nix") || os.contains("nux") || os.contains("aix")) {
                    return OSName.LINUX;
                }
                return null;
            }
            
            private String getUserHome() {
                String home = System.getProperty("user.home");
                if (home != null && home.endsWith("/")) {
                    home = home.substring(0, home.length() - 1);
                }
                return home;
            }

        private List<String> getInstallationPaths(OSName os) {

        List<String> paths;
        switch (os) {
            case LINUX:
            case SOLARIS:
                paths = Arrays.asList(
                    getUserHome() + "/.local/share/jupyter/kernels",
                    "/usr/local/share/jupyter/kernels",
                    "/usr/share/jupyter/kernels"
                );
                break;
            case MAC:
                paths = Arrays.asList(
                    getUserHome() + "/Library/Jupyter/kernels",
                    "/usr/local/share/jupyter/kernels",
                    "/usr/share/jupyter/kernels"
                );
                break;
            case WINDOWS:
                paths = Arrays.asList(
                    System.getenv("APPDATA") + "/jupyter/kernels",
                    System.getenv("PROGRAMDATA") + "/jupyter/kernels"
                );
                break;
            default:
                paths = new ArrayList<>();
        }
        return paths;
    }

    /**
     * Looks for command in jbang bin dir and then in PATH
     * @param cmd
     * @return
     */
    private Path findCommand(String cmd) {
        Path command = null;
        List<String> paths = new ArrayList<>();

        paths.add(System.getProperty("user.home") + "/.jbang/bin");

        paths.addAll(Arrays.asList(System.getenv("PATH").split(File.pathSeparator)));

        for (String path : paths) {
            if (os.equals(OSName.WINDOWS)) {
                command = Path.of(path, cmd + ".cmd");
                if (exists(command) && Files.isExecutable(command)) {
                    break;
                } 
                command = Path.of(path, cmd + ".ps1");
                if (exists(command) && Files.isExecutable(command)) {
                    break;
                } 
            }
            command = Path.of(path, cmd);
            if (exists(command) && Files.isExecutable(command)) {
                break;
            }
            command = null;
        }

        return command;
    }
    public static void main(String... args) {
        int exitCode = new CommandLine(new installkernel()).setCaseInsensitiveEnumValuesAllowed(true).execute(args);
        System.exit(exitCode);
    }

     private KernelJson generateProxyKernelJson(KernelJson kernelJson) {

        String proxyApp = loadResource("ipc_proxy_kernel.py");
        String pycmd;

        Path pythonCommand = findCommand("python");
        if (pythonCommand == null) {
            pythonCommand = findCommand("python3");
        }
        if (pythonCommand == null) {
            throw new IllegalStateException("Python executable not found in PATH. Please ensure it is available before installing kernel.");
        }
        pycmd = pythonCommand.toAbsolutePath().toString();

         KernelJson proxyKernel = new KernelJson(
                        List.of(pycmd, 
                             "{{KERNEL_DIR}}/ipc_proxy_kernel.py", 
                                CONNECTION_FILE_MARKER, 
                                "--kernel="+kernelJson.kernelDir), 
                        name(), 
                        kernel.language(), 
                        INTERRUPT_MODE, 
                        Map.of(),
                        kernelDir(),
                        Map.of(Path.of("ipc_proxy_kernel.py"), proxyApp));

        return proxyKernel;
    }

    KernelJson generateJavaKernelJson(String postfix) {
        Path command = findCommand("jbang");
        if (command == null) {
            throw new IllegalStateException("jbang executable not found. Please ensure it is available before running install kernel.");
        }
        var commandList = new ArrayList<String>(); 
        commandList.add(command.toAbsolutePath().toString());
        commandList.add("--java");
        commandList.add(kernel.javaVersion());
        
        if(kernel.mainClass()!=null) {
            commandList.add("-m");
            commandList.add(kernel.mainClass());
        }

        if(kernel.modules().size()>0) {
            commandList.add("-R--add-modules");
            commandList.add("-R" + String.join(",", kernel.modules()));
        }

        kernel.jvmArguments().forEach(jvmArg -> {
            commandList.add("-R" + jvmArg);
        });
        
        commandList.add(kernel.gav());
        
        
        commandList.addAll(kernel.arguments());

        KernelJson json = new KernelJson(commandList, 
                                name() + postfix, 
                                kernel.language(), 
                                INTERRUPT_MODE, 
                                kernel.options(compilerOptions, timeout),
                                kernelDir() + postfix,
                                Map.of());
        return json;
    }

    static OSName os;

    @Override
    public Integer call() throws Exception { 
        
        os = findOSName();
        if (os == null) {
            throw new RuntimeException("Operating system is not recognized. Installation failed.");
        }

        String postfix = "";
        if(useIPC || "ipc".equals(System.getenv("COLAB_JUPYTER_TRANSPORT"))) {
            useIPC = true;
            postfix = "-tcp";
        }


        List<String> installationPath = null;
        if(jupyterKernelDir!=null) {
            installationPath = List.of(jupyterKernelDir);
        } else {
            installationPath = getInstallationPaths(os);
        }

        verbose("Considering " + String.join(",", installationPath));
        
        if(!exists(Paths.get(installationPath.get(0)))) {
            System.out.println("Creating " + installationPath.get(0));
            Files.createDirectories(Paths.get(installationPath.get(0)));
           // throw new IllegalStateException("Jupyter Kernel path " + installationPath.get(0) + " does not exist. Please ensure it is available before trying to install a kernel.");
        }

        
        KernelJson json = generateJavaKernelJson(postfix);
        writeKernel(installationPath.get(0), json);

        if(useIPC) {
            json = generateProxyKernelJson(json);
            writeKernel(installationPath.get(0), json);
        }
        
        if(kernel.info()!=null) {
                out.println("For more information on this specific kernel: " + kernel.info());
        }
        out.println("\nBrought to you by https://github.com/jupyter-java");
        return 0;
    }

  

    private void writeKernel(String installationPath, KernelJson original) throws JsonProcessingException {
        ObjectMapper objectMapper = setupObjectMapper();

        var fullKernelDir = Paths.get(installationPath, original.kernelDir).toAbsolutePath().toString();
        var output = Paths.get(fullKernelDir, "kernel.json");

        final var json = new KernelJson(original.argv.stream().map(arg -> arg.replace("{{KERNEL_DIR}}", fullKernelDir)).collect(Collectors.toList()), 
                                original.displayName, 
                                original.language, 
                                original.interruptMode, 
                                original.env,
                                original.kernelDir,
                                original.resources);

        String jsonString = objectMapper.writeValueAsString(json);

        verbose(format("Writing: %s\nto %s", jsonString, output));
        try {
            if (!exists(output.getParent())) {
                createDirectories(output.getParent());
            }
            write(output, jsonString.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            out.println(json.displayName + " kernel installed to " + output);
            json.resources.forEach((path, content) -> {
                try {
                    Path resource = Paths.get(installationPath, json.kernelDir, path.toString());
                    System.out.println(format("Additional file: %s", resource));
                    if (!exists(resource.getParent())) {
                        createDirectories(resource.getParent());
                    }
                    write(resource, content.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                } catch (IOException e) {
                   System.err.println("Could not write resource " + path + " to " + output);
                }
            });

            
       } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private ObjectMapper setupObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        DefaultPrettyPrinter prettyPrinter = new DefaultPrettyPrinter();
        prettyPrinter.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
        objectMapper.setDefaultPrettyPrinter(prettyPrinter);
        return objectMapper;
    }

    void verbose(String msg) { 
        if(verbose) {
            out.println(msg);
        }
    }

    public static String loadResource(String resourcePath) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try (InputStream inputStream = classLoader.getResourceAsStream(resourcePath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new IllegalStateException("Could not load resource: " + resourcePath, e);
        }
    } 
    

public class KernelJson {
    public final List<String> argv;
    public final String displayName;
    public final String language;
    public final String interruptMode;
    public final Map<String, String> env;
    @JsonIgnore public final String kernelDir;
    @JsonIgnore public final Map<Path, String> resources;

    public KernelJson(List<String> argv, String displayName, String language, String interruptMode, Map<String, String> env, String kernelDir, Map<Path, String> resources) {
        this.argv = argv;
        this.displayName = displayName;
        this.language = language;
        this.interruptMode = interruptMode;
        this.env = env;
        this.kernelDir = kernelDir;
        this.resources = resources;
    }
}
}
