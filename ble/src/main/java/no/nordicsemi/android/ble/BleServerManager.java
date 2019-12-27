package no.nordicsemi.android.ble;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.UUID;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import no.nordicsemi.android.ble.utils.ILogger;

/**
 * The manager for local GATT server. To be used with one or more instances of {@link BleManager}
 *
 * @since 2.2
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public abstract class BleServerManager<E extends BleServerManagerCallbacks> implements ILogger {
	public final static UUID CHARACTERISTIC_EXTENDED_PROPERTIES_DESCRIPTOR_UUID = UUID.fromString("00002900-0000-1000-8000-00805f9b34fb");
	public final static UUID CLIENT_USER_DESCRIPTION_DESCRIPTOR_UUID            = UUID.fromString("00002901-0000-1000-8000-00805f9b34fb");
	public final static UUID CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR_UUID       = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

	/** Bluetooth GATT server instance, or null if not opened. */
	private BluetoothGattServer server;

	private final List<BleManager> managers = new ArrayList<>();
	private final Context context;
	private E userCallbacks;

	/**
	 * List of server services returned by {@link #initializeServer()}.
	 * This list is empties when the services are being added one by one to the server.
	 * To get the server services, use {@link BluetoothGattServer#getServices()} instead.
	 */
	private Queue<BluetoothGattService> serverServices;

	private List<BluetoothGattCharacteristic> sharedCharacteristics;
	private List<BluetoothGattDescriptor> sharedDescriptors;

	public BleServerManager(@NonNull final Context context) {
		this.context = context;
	}

	/**
	 * Opens the GATT server and starts initializing services. This method only starts initializing
	 * services. The {@link BleServerManagerCallbacks#onServerReady()} will be called when all
	 * services are done.
	 *
	 * @return true, if the server has been started successfully. If GATT server could not
	 * be started, for example the Bluetooth is disabled, false is returned.
	 * @see #close()
	 */
	public final boolean open() {
		if (server != null)
			return true;

		serverServices = new LinkedList<>(initializeServer());
		final BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
		if (bm != null) {
			server = bm.openGattServer(context, gattServerCallback);
		}
		if (server != null) {
			log(Log.INFO, "[Server] Server started successfully");
			try {
				final BluetoothGattService service = serverServices.remove();
				server.addService(service);
			} catch (final NoSuchElementException e) {
				if (userCallbacks != null)
					userCallbacks.onServerReady();
			} catch (final Exception e) {
				close();
				return false;
			}
			return true;
		}
		log(Log.WARN, "GATT server initialization failed");
		serverServices = null;
		return false;
	}

	/**
	 * Closes the GATT server.
	 */
	public final void close() {
		if (server != null) {
			server.close();
			server = null;
		}
		serverServices = null;
		for (BleManager manager: managers) {
			manager.close();
		}
		managers.clear();
	}

	/**
	 * Sets the manager callback listener.
	 *
	 * @param callbacks the callback listener.
	 */
	public final void setManagerCallbacks(@NonNull final E callbacks) {
		userCallbacks = callbacks;
	}

	/**
	 * Returns the {@link BluetoothGattServer} instance.
	 */
	@Nullable
	final BluetoothGattServer getServer() {
		return server;
	}

	/**
	 * Adds the BLE Manager to be handled.
	 * @param manager the Ble Manager.
	 */
	final void addManager(@NonNull final BleManager manager) {
		if (!managers.contains(manager)) {
			managers.add(manager);
		}
	}

	/**
	 * Removes the manager. Callbacks will no longer be passed to it.
	 * @param manager the manager to be removed.
	 */
	final void removeManager(@NonNull final BleManager manager) {
		managers.remove(manager);
	}

	final boolean isShared(@NonNull final BluetoothGattCharacteristic characteristic) {
		return sharedCharacteristics != null && sharedCharacteristics.contains(characteristic);
	}

	final boolean isShared(@NonNull final BluetoothGattDescriptor descriptor) {
		return sharedDescriptors != null && sharedDescriptors.contains(descriptor);
	}

	@Nullable
	private BleManagerHandler getRequestHandler(@NonNull final BluetoothDevice device) {
		for (final BleManager manager : managers) {
			if (device.equals(manager.getBluetoothDevice())) {
				return manager.requestHandler;
			}
		}
		return null;
	}

	@Override
	public void log(final int priority, @NonNull final String message) {
		// Override to log events. Simple log can use Logcat:
		//
		// Log.println(priority, TAG, message);
		//
		// You may also use Timber:
		//
		// Timber.log(priority, message);
		//
		// or nRF Logger:
		//
		// Logger.log(logSession, LogContract.Log.Level.fromPriority(priority), message);
		//
		// Starting from nRF Logger 2.1.3, you may use log-timber and plant nRFLoggerTree.
		// https://github.com/NordicSemiconductor/nRF-Logger-API
	}

	@Override
	public void log(final int priority, @StringRes final int messageRes,
					@Nullable final Object... params) {
		final String message = context.getString(messageRes, params);
		log(priority, message);
	}

	/**
	 * This method is called once, just after instantiating the {@link BleServerManager}.
	 * It should return a list of server GATT services that will be available for the remote device
	 * to use. You may use {@link #service(UUID, BluetoothGattCharacteristic...)} to easily
	 * instantiate a service.
	 * <p>
	 * Server services will be added to the local GATT configuration on the Android device.
	 * The library does not know what services are already set up by other apps or
	 * {@link BleServerManager} instances, so a UUID collision is possible.
	 * The remote device will discover all services set up by all apps.
	 * <p>
	 * In order to enable server callbacks (see {@link android.bluetooth.BluetoothGattServerCallback}),
	 * but without defining own services, return an empty list.
	 *
	 * @since 2.2
	 * @return The list of server GATT services, or null if no services should be created. An
	 * empty array to start the GATT server without any services.
	 */
	@NonNull
	protected abstract List<BluetoothGattService> initializeServer();

	/**
	 * A helper method for creating a primary service with given UUID and list of characteristics.
	 * This method can be called from {@link #initializeServer()}.
	 *
	 * @param uuid The service UUID.
	 * @param characteristics The optional list of characteristics.
	 * @return The new service.
	 */
	@NonNull
	protected final BluetoothGattService service(@NonNull final UUID uuid, final BluetoothGattCharacteristic... characteristics) {
		final BluetoothGattService service = new BluetoothGattService(uuid, BluetoothGattService.SERVICE_TYPE_PRIMARY);
		for (BluetoothGattCharacteristic characteristic : characteristics) {
			service.addCharacteristic(characteristic);
		}
		return service;
	}

	/**
	 * A helper method that creates a characteristic with given UUID, properties and permissions.
	 * Optionally, an initial value and a list of descriptors may be set.
	 * If {@link #reliableWrite()} was added as one of the descriptors or the Characteristic User
	 * Description descriptor was created with any of write permissions
	 * (see {@link #description(String, boolean)}) the
	 * {@link BluetoothGattCharacteristic#PROPERTY_EXTENDED_PROPS} property will be added automatically.
	 * <p>
	 * The value of the characteristic will NOT be shared between clients. Each client will write
	 * and read its own copy. To create a shared characteristic, use
	 * {@link #sharedCharacteristic(UUID, int, int, byte[], BluetoothGattDescriptor...)} instead.
	 *
	 * @param uuid The characteristic UUID.
	 * @param properties The bit mask of characteristic properties. See {@link BluetoothGattCharacteristic}
	 *                   for details.
	 * @param permissions The bit mask or characteristic permissions. See {@link BluetoothGattCharacteristic}
	 *                    for details.
	 * @param initialValue The optional initial value of the characteristic.
	 * @param descriptors The optional list of descriptors.
	 * @return The characteristic.
	 */
	@NonNull
	protected final BluetoothGattCharacteristic characteristic(@NonNull final UUID uuid,
															   int properties, final int permissions,
															   @Nullable final byte[] initialValue,
															   final BluetoothGattDescriptor... descriptors) {
		// Look Characteristic User Description descriptor and Characteristic Extended Properties descr.
		boolean writableAuxiliaries = false;
		boolean cepFound = false;
		BluetoothGattDescriptor cep = null;
		for (final BluetoothGattDescriptor descriptor : descriptors) {
			if (CLIENT_USER_DESCRIPTION_DESCRIPTOR_UUID.equals(descriptor.getUuid())
					&& (descriptor.getPermissions() & 0x70) != 0) {
				writableAuxiliaries = true;
			} else if (CHARACTERISTIC_EXTENDED_PROPERTIES_DESCRIPTOR_UUID.equals(descriptor.getUuid())) {
				cep = descriptor;
				cepFound = true;
			}
		}

		if (writableAuxiliaries) {
			if (cep == null) {
				cep = new BluetoothGattDescriptor(CHARACTERISTIC_EXTENDED_PROPERTIES_DESCRIPTOR_UUID,
						BluetoothGattDescriptor.PERMISSION_READ);
				cep.setValue(new byte[]{0x02, 0x00});
			} else {
				if (cep.getValue() != null && cep.getValue().length == 2) {
					cep.getValue()[0] |= 0x02;
				} else {
					cep.setValue(new byte[]{0x02, 0x00});
				}
			}
		}

		final boolean reliableWrite = cep != null && cep.getValue() != null
				&& cep.getValue().length == 2 && (cep.getValue()[0] & 0x01) != 0;
		if (writableAuxiliaries || reliableWrite) {
			properties |= BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS;
		}
		if ((properties & BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS) != 0 && cep == null) {
			cep = new BluetoothGattDescriptor(CHARACTERISTIC_EXTENDED_PROPERTIES_DESCRIPTOR_UUID, BluetoothGattDescriptor.PERMISSION_READ);
			cep.setValue(new byte[] { 0, 0 });
		}

		final BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(uuid, properties, permissions);
		for (BluetoothGattDescriptor descriptor: descriptors) {
			characteristic.addDescriptor(descriptor);
		}
		if (cep != null && !cepFound) {
			characteristic.addDescriptor(cep);
		}
		characteristic.setValue(initialValue);
		return characteristic;
	}

	/**
	 * A helper method that creates a characteristic with given UUID, properties and permissions.
	 * Optionally, an initial value and a list of descriptors may be set.
	 * If {@link #reliableWrite()} was added as one of the descriptors or the Characteristic User
	 * Description descriptor was created with any of write permissions
	 * (see {@link #description(String, boolean)}) the
	 * {@link BluetoothGattCharacteristic#PROPERTY_EXTENDED_PROPS} property will be added automatically.
	 * <p>
	 * The value of the characteristic is shared between clients. A value written by one of the
	 * connected clients will be available for all other clients. To create a sandboxed characteristic,
	 * use {@link #characteristic(UUID, int, int, byte[], BluetoothGattDescriptor...)} instead.
	 *
	 * @param uuid The characteristic UUID.
	 * @param properties The bit mask of characteristic properties. See {@link BluetoothGattCharacteristic}
	 *                   for details.
	 * @param permissions The bit mask or characteristic permissions. See {@link BluetoothGattCharacteristic}
	 *                    for details.
	 * @param initialValue The optional initial value of the characteristic.
	 * @param descriptors The optional list of descriptors.
	 * @return The characteristic.
	 */
	@NonNull
	protected final BluetoothGattCharacteristic sharedCharacteristic(@NonNull final UUID uuid,
																	 final int properties, final int permissions,
																	 @Nullable final byte[] initialValue,
																	 final BluetoothGattDescriptor... descriptors) {
		final BluetoothGattCharacteristic characteristic = characteristic(uuid, properties, permissions, initialValue, descriptors);
		if (sharedCharacteristics == null)
			sharedCharacteristics = new ArrayList<>();
		sharedCharacteristics.add(characteristic);
		return characteristic;
	}

	@NonNull
	protected final BluetoothGattDescriptor descriptor(@NonNull final UUID uuid, final int permissions,
													   @Nullable final byte[] initialValue) {
		final BluetoothGattDescriptor descriptor = new BluetoothGattDescriptor(uuid, permissions);
		descriptor.setValue(initialValue);
		return descriptor;
	}

	@NonNull
	protected final BluetoothGattDescriptor sharedDescriptor(@NonNull final UUID uuid,
															 final int permissions,
															 @Nullable final byte[] initialValue) {
		final BluetoothGattDescriptor descriptor = descriptor(uuid, permissions, initialValue);
		if (sharedDescriptors == null)
			sharedDescriptors = new ArrayList<>();
		sharedDescriptors.add(descriptor);
		return descriptor;
	}

	/**
	 * This helper method returns a new instance of Client Characteristic Configuration Descriptor
	 * (CCCD) that can be added to a server characteristic in {@link #initializeServer()}.
	 *
	 * @return The CCC descriptor used to enable and disable notifications or indications.
	 */
	@NonNull
	protected final BluetoothGattDescriptor cccd() {
		return descriptor(CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR_UUID,
				BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE,
				new byte[] { 0, 0 });
	}

	/**
	 * This helper method returns a new instance of Characteristic Extended Properties descriptor
	 * that can be added to a server characteristic.
	 * This descriptor should be added it {@link BluetoothGattCharacteristic#PROPERTY_EXTENDED_PROPS}
	 * property is set.
	 * @return The CEP descriptor with Reliable Write bit set.
	 */
	@NonNull
	protected final BluetoothGattDescriptor reliableWrite() {
		return sharedDescriptor(CHARACTERISTIC_EXTENDED_PROPERTIES_DESCRIPTOR_UUID,
				BluetoothGattDescriptor.PERMISSION_READ,
				new byte[] { 1, 0 });
	}

	/**
	 * This helper method returns a new instance of Client User Description Descriptor
	 * that can be added to a server characteristic in {@link #initializeServer()}.
	 *
	 * @param description the UTF-8 string that is a user textual description of the characteristic.
	 * @param writableAuxiliaries if true, the descriptor will be writable and the Writable Auxiliaries
	 *                            bit in Characteristic Extended Properties descriptor will be set.
	 *                            See Vol. 3, Part F, Section 3.3.3.2 in Bluetooth Core specification 5.1.
	 * @return The User Description descriptor.
	 */
	@NonNull
	protected final BluetoothGattDescriptor description(@Nullable final String description, final boolean writableAuxiliaries) {
		final BluetoothGattDescriptor cud = descriptor(CLIENT_USER_DESCRIPTION_DESCRIPTOR_UUID,
				BluetoothGattDescriptor.PERMISSION_READ | (writableAuxiliaries ? BluetoothGattDescriptor.PERMISSION_WRITE : 0),
				description != null ? description.getBytes() : null);
		if (!writableAuxiliaries)
			sharedDescriptors.add(cud);
		return cud;
	}

	private final BluetoothGattServerCallback gattServerCallback = new BluetoothGattServerCallback() {

		@Override
		public void onServiceAdded(final int status, @NonNull final BluetoothGattService service) {
			if (status == BluetoothGatt.GATT_SUCCESS) {
				try {
					final BluetoothGattService nextService = serverServices.remove();
					server.addService(nextService);
				} catch (final Exception e) {
					log(Log.INFO, "[Server] All services added successfully");
					if (userCallbacks != null)
						userCallbacks.onServerReady();
					serverServices = null;
				}
			} else {
				log(Log.ERROR, "[Server] Adding service failed with error " + status);
			}
		}

		@Override
		public void onConnectionStateChange(@NonNull final BluetoothDevice device, final int status, final int newState) {
			if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothGatt.STATE_CONNECTED) {
				if (userCallbacks != null)
					userCallbacks.onDeviceConnectedToServer(device);
			} else {
				if (userCallbacks != null)
					userCallbacks.onDeviceDisconnectedFromServer(device);
			}
		}

		@Override
		public void onCharacteristicReadRequest(@NonNull final BluetoothDevice device,
												final int requestId, final int offset,
												@NonNull final BluetoothGattCharacteristic characteristic) {
			final BleManagerHandler handler = getRequestHandler(device);
			if (handler != null) {
				handler.onCharacteristicReadRequest(server, device, requestId, offset, characteristic);
			}
		}

		@Override
		public void onCharacteristicWriteRequest(@NonNull final BluetoothDevice device, final int requestId,
												 @NonNull final BluetoothGattCharacteristic characteristic,
												 final boolean preparedWrite, final boolean responseNeeded,
												 final int offset, @NonNull final byte[] value) {
			final BleManagerHandler handler = getRequestHandler(device);
			if (handler != null) {
				handler.onCharacteristicWriteRequest(server, device, requestId, characteristic,
						preparedWrite, responseNeeded, offset, value);
			}
		}

		@Override
		public void onDescriptorReadRequest(@NonNull final BluetoothDevice device, final int requestId, final int offset,
											@NonNull final BluetoothGattDescriptor descriptor) {
			final BleManagerHandler handler = getRequestHandler(device);
			if (handler != null) {
				handler.onDescriptorReadRequest(server, device, requestId, offset, descriptor);
			}
		}

		@Override
		public void onDescriptorWriteRequest(@NonNull final BluetoothDevice device, final int requestId,
											 @NonNull final BluetoothGattDescriptor descriptor,
											 final boolean preparedWrite, final boolean responseNeeded,
											 final int offset, @NonNull final byte[] value) {
			final BleManagerHandler handler = getRequestHandler(device);
			if (handler != null) {
				handler.onDescriptorWriteRequest(server, device, requestId, descriptor,
						preparedWrite, responseNeeded, offset, value);
			}
		}

		@Override
		public void onExecuteWrite(@NonNull final BluetoothDevice device, final int requestId,
								   final boolean execute) {
			final BleManagerHandler handler = getRequestHandler(device);
			if (handler != null) {
				handler.onExecuteWrite(server, device, requestId, execute);
			}
		}

		@Override
		public void onNotificationSent(@NonNull final BluetoothDevice device, final int status) {
			final BleManagerHandler handler = getRequestHandler(device);
			if (handler != null) {
				handler.onNotificationSent(server, device, status);
			}
		}

		@Override
		public void onMtuChanged(@NonNull final BluetoothDevice device, final int mtu) {
			final BleManagerHandler handler = getRequestHandler(device);
			if (handler != null) {
				handler.onMtuChanged(server, device, mtu);
			}
		}
	};
}
