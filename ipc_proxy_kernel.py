## Purpose: This script is used to proxy the connection between the Jupyter notebook and a java
##          kernel. This is necessary when ipc is used like in google colab as java zmq implementation
##          not yet implements ipc.
## Original source: https://gist.github.com/SpencerPark/e2732061ad19c1afa4a33a58cb8f18a9
## StackOverflow: https://stackoverflow.com/questions/74674688/google-colab-notebook-using-ijava-stuck-at-connecting-after-installation-ref

import argparse
import json
from threading import Thread

import zmq
from jupyter_client import KernelClient
from jupyter_client.channels import HBChannel
from jupyter_client.manager import KernelManager
from jupyter_client.session import Session
from traitlets.traitlets import Type

parser = argparse.ArgumentParser()
parser.add_argument("connection_file")
parser.add_argument("--kernel", type=str, required=True)
args = parser.parse_args()

# parse connection file details
with open(args.connection_file, "r") as connection_file:
    connection_file_contents = json.load(connection_file)

    transport = str(connection_file_contents["transport"])
    ip = str(connection_file_contents["ip"])

    shell_port = int(connection_file_contents["shell_port"])
    stdin_port = int(connection_file_contents["stdin_port"])
    control_port = int(connection_file_contents["control_port"])
    iopub_port = int(connection_file_contents["iopub_port"])
    hb_port = int(connection_file_contents["hb_port"])

    signature_scheme = str(connection_file_contents["signature_scheme"])
    key = str(connection_file_contents["key"]).encode()

# channel | kernel_type | client_type
# shell   | ROUTER      | DEALER
# stdin   | ROUTER      | DEALER
# ctrl    | ROUTER      | DEALER
# iopub   | PUB         | SUB
# hb      | REP         | REQ

zmq_context = zmq.Context()


def create_and_bind_socket(port: int, socket_type: int):
    if port <= 0:
        raise ValueError(f"Invalid port: {port}")

    if transport == "tcp":
        addr = f"tcp://{ip}:{port}"
    elif transport == "ipc":
        addr = f"ipc://{ip}-{port}"
    else:
        raise ValueError(f"Unknown transport: {transport}")

    socket: zmq.Socket = zmq_context.socket(socket_type)
    socket.linger = 1000  # ipykernel does this
    socket.bind(addr)
    return socket


shell_socket = create_and_bind_socket(shell_port, zmq.ROUTER)
stdin_socket = create_and_bind_socket(stdin_port, zmq.ROUTER)
control_socket = create_and_bind_socket(control_port, zmq.ROUTER)
iopub_socket = create_and_bind_socket(iopub_port, zmq.PUB)
hb_socket = create_and_bind_socket(hb_port, zmq.REP)

# Proxy and the real kernel have their own heartbeats. (shoutout to ipykernel
# for this neat little heartbeat implementation)
Thread(target=zmq.device, args=(zmq.QUEUE, hb_socket, hb_socket)).start()


def ZMQProxyChannel_factory(proxy_server_socket: zmq.Socket):
    class ZMQProxyChannel(object):
        kernel_client_socket: zmq.Socket = None
        session: Session = None

        def __init__(self, socket: zmq.Socket, session: Session, _=None):
            super().__init__()
            self.kernel_client_socket = socket
            self.session = session

        def start(self):
            # Very convenient zmq device here, proxy will handle the actual zmq
            # proxying on each of our connected sockets (other than heartbeat).
            # It blocks while they are connected so stick it in a thread.
            Thread(
                target=zmq.proxy,
                args=(proxy_server_socket, self.kernel_client_socket),
            ).start()

        def stop(self):
            if self.kernel_client_socket is not None:
                try:
                    self.kernel_client_socket.close(linger=0)
                except Exception:
                    pass
                self.kernel_client_socket = None

        def is_alive(self):
            return self.kernel_client_socket is not None

    return ZMQProxyChannel


class ProxyKernelClient(KernelClient):
    shell_channel_class = Type(ZMQProxyChannel_factory(shell_socket))
    stdin_channel_class = Type(ZMQProxyChannel_factory(stdin_socket))
    control_channel_class = Type(ZMQProxyChannel_factory(control_socket))
    iopub_channel_class = Type(ZMQProxyChannel_factory(iopub_socket))
    hb_channel_class = Type(HBChannel)


kernel_manager = KernelManager()
kernel_manager.kernel_name = args.kernel
kernel_manager.transport = "tcp"
kernel_manager.client_factory = ProxyKernelClient
kernel_manager.autorestart = False

# Make sure the wrapped kernel uses the same session info. This way we don't
# need to decode them before forwarding, we can directly pass everything
# through.
kernel_manager.session.signature_scheme = signature_scheme
kernel_manager.session.key = key

kernel_manager.start_kernel()

# Connect to the real kernel we just started and start up all the proxies.
kernel_client: ProxyKernelClient = kernel_manager.client()
kernel_client.start_channels()


# Everything should be up and running. We now just wait for the managed kernel
# process to exit and when that happens, shutdown and exit with the same code.
exit_code = kernel_manager.kernel.wait()
kernel_client.stop_channels()
zmq_context.destroy(0)
exit(exit_code)
