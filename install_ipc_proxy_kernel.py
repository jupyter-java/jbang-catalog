import argparse
import json
import os
import os.path
import shutil
import sys

from jupyter_client.kernelspec import KernelSpec, KernelSpecManager, NoSuchKernel

parser = argparse.ArgumentParser()
parser.add_argument("--kernel", type=str, required=True)
parser.add_argument("--implementation", type=str, required=True)
parser.add_argument("--quiet", action="store_true", default=False)
args = parser.parse_args()


def log(*log_args):
    if not args.quiet:
        print(*log_args)


kernel_spec_manager = KernelSpecManager()
try:
    real_kernel_spec: KernelSpec = kernel_spec_manager.get_kernel_spec(args.kernel)
except NoSuchKernel:
    print(f"No kernel installed with name {args.kernel}. Available kernels:")
    for name, path in kernel_spec_manager.find_kernel_specs().items():
        print(f"  - {name}\t{path}")
    exit(1)

log(f"Moving {args.kernel} kernel from {real_kernel_spec.resource_dir}...")

real_kernel_install_path = real_kernel_spec.resource_dir
new_kernel_name = f"{args.kernel}_tcp"
new_kernel_install_path = os.path.join(
    os.path.dirname(real_kernel_install_path), new_kernel_name
)
shutil.move(real_kernel_install_path, new_kernel_install_path)

# Update the moved kernel name and args. We tag it _tcp because the proxy will
# impersonate it and should be the one using the real name.
new_kernel_json_path = os.path.join(new_kernel_install_path, "kernel.json")
with open(new_kernel_json_path, "r") as in_:
    real_kernel_json = json.load(in_)
    real_kernel_json["name"] = new_kernel_name
    real_kernel_json["argv"] = list(
        map(
            lambda arg: arg.replace(real_kernel_install_path, new_kernel_install_path),
            real_kernel_json["argv"],
        )
    )

with open(new_kernel_json_path, "w") as out:
    json.dump(real_kernel_json, out)

log(f"Wrote modified kernel.json for {new_kernel_name} in {new_kernel_json_path}")

log(
    f"Installing the proxy kernel in place of {args.kernel} in {real_kernel_install_path}"
)
os.makedirs(real_kernel_install_path)

proxy_kernel_implementation_path = os.path.join(
    real_kernel_install_path, "ipc_proxy_kernel.py"
)

proxy_kernel_spec = KernelSpec()
proxy_kernel_spec.argv = [
    sys.executable,
    proxy_kernel_implementation_path,
    "{connection_file}",
    f"--kernel={new_kernel_name}",
]
proxy_kernel_spec.display_name = real_kernel_spec.display_name
proxy_kernel_spec.interrupt_mode = real_kernel_spec.interrupt_mode or "message"
proxy_kernel_spec.language = real_kernel_spec.language

proxy_kernel_json_path = os.path.join(real_kernel_install_path, "kernel.json")
with open(proxy_kernel_json_path, "w") as out:
    json.dump(proxy_kernel_spec.to_dict(), out, indent=2)
log(f"Installed proxy kernelspec: {proxy_kernel_spec.to_json()}")

shutil.copy(args.implementation, proxy_kernel_implementation_path)

print("Proxy kernel installed. Go to 'Runtime > Change runtime type' and select 'java'")
