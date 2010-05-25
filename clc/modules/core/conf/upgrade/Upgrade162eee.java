import edu.ucsb.eucalyptus.cloud.entities.AOEMetaInfo;
import edu.ucsb.eucalyptus.cloud.entities.AOEVolumeInfo;
import edu.ucsb.eucalyptus.cloud.entities.ARecordInfo;
import edu.ucsb.eucalyptus.cloud.entities.CNAMERecordInfo;
import edu.ucsb.eucalyptus.cloud.entities.ClusterInfo;
import edu.ucsb.eucalyptus.cloud.entities.ISCSIMetaInfo;
import edu.ucsb.eucalyptus.cloud.entities.ISCSIVolumeInfo;
import edu.ucsb.eucalyptus.cloud.entities.ImageCacheInfo;
import edu.ucsb.eucalyptus.cloud.entities.LVMVolumeInfo;
import edu.ucsb.eucalyptus.cloud.entities.MetaDataInfo;
import edu.ucsb.eucalyptus.cloud.entities.NSRecordInfo;
import edu.ucsb.eucalyptus.cloud.entities.SOARecordInfo;
import edu.ucsb.eucalyptus.cloud.entities.SnapshotInfo;
import edu.ucsb.eucalyptus.cloud.entities.StorageInfo;
import edu.ucsb.eucalyptus.cloud.entities.StorageStatsInfo;
import edu.ucsb.eucalyptus.cloud.entities.SystemConfiguration;
import edu.ucsb.eucalyptus.cloud.entities.TorrentInfo;
import edu.ucsb.eucalyptus.cloud.entities.VolumeInfo;
import edu.ucsb.eucalyptus.cloud.entities.WalrusInfo;
import edu.ucsb.eucalyptus.cloud.entities.WalrusSnapshotInfo;
import edu.ucsb.eucalyptus.cloud.entities.WalrusStatsInfo;
import edu.ucsb.eucalyptus.cloud.entities.ZoneInfo;
import groovy.sql.GroovyRowResult;
import groovy.sql.Sql;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.persistence.Column;
import javax.persistence.MappedSuperclass;
import javax.persistence.PersistenceContext;
import javax.persistence.Table;

import org.apache.log4j.Logger;

import com.eucalyptus.address.Address;
import com.eucalyptus.blockstorage.Snapshot;
import com.eucalyptus.blockstorage.Volume;
import com.eucalyptus.config.ClusterConfiguration;
import com.eucalyptus.config.StorageControllerConfiguration;
import com.eucalyptus.config.WalrusConfiguration;
import com.eucalyptus.entities.AbstractPersistent;
import com.eucalyptus.entities.Counters;
import com.eucalyptus.entities.EntityWrapper;
import com.eucalyptus.images.ImageInfo;
import com.eucalyptus.upgrade.StandalonePersistence;
import com.eucalyptus.upgrade.UpgradeScript;

class UpgradeWalrus162eee implements UpgradeScript {
	static final String FROM_VERSION = "1.6.2";
	static final String TO_VERSION = "eee-2.0.0";
	private static Logger LOG = Logger.getLogger( UpgradeWalrus162eee.class );
	private static List<Class> entities = new ArrayList<Class>();
	private static Map<String, Class> entityMap = new HashMap<String, Class>();

	@Override
	public Boolean accepts( String from, String to ) {
		if(FROM_VERSION.equals(from) && TO_VERSION.equals(to))
			return true;
		return false;
	}

	@Override
	public void upgrade(File oldEucaHome, File newEucaHome) {
		buildEntityMap();
		Set<String> entityKeys = entityMap.keySet();
		for (String entityKey : entityKeys) {
			String contextName = getContextName(entityKey);
			Sql conn = getConnection(contextName);
			if (conn != null) {
				Map<String, Method> setterMap = buildSetterMap(conn, entityKey);
				if(setterMap != null)
					doUpgrade(contextName, conn, entityKey, setterMap);
			}
		}
	}

	private Sql getConnection(String contextName) {
		try {
			Sql conn = StandalonePersistence.getConnection(contextName);
			LOG.info("Getting context: " + contextName);
			return conn;
		} catch (SQLException e) {
			LOG.error(e);
			return null;
		}
	}

	private String getContextName(String entityKey) {
		Class entity = entityMap.get(entityKey);
		if (entity != null) {
			if(entity.isAnnotationPresent(PersistenceContext.class)) {
				PersistenceContext annot = (PersistenceContext) entity.getAnnotation(PersistenceContext.class);
				return annot.name();
			}
		}
		return null;		
	}

	private void doUpgrade(String contextName, Sql conn, String entityKey, Map<String, Method> setterMap) {
		List<GroovyRowResult> rowResults;
		try {
			rowResults = conn.rows("SELECT * FROM " + entityKey);
			EntityWrapper db =  new EntityWrapper(contextName);
			for (GroovyRowResult rowResult : rowResults) {
				Set<String> columns = rowResult.keySet();
				Object dest;
				try {
					dest = ClassLoader.getSystemClassLoader().loadClass(entityMap.get(entityKey).getCanonicalName()).newInstance();
				} catch (ClassNotFoundException e1) {
					LOG.error(e1);
					break;
				} catch (InstantiationException e) {
					LOG.error(e);
					break;
				} catch (IllegalAccessException e) {
					LOG.error(e);
					break;
				}
				for (String column : columns) {
					Method setter = setterMap.get(column);
					if(setter != null) {
						Object o = rowResult.get(column);
						if(o != null) {
							try {
								setter.invoke(dest, o);
							} catch (IllegalArgumentException e) {
								LOG.error(e);
							} catch (IllegalAccessException e) {
								LOG.error(e);
							} catch (InvocationTargetException e) {
								LOG.error(e);
							}
						}
					}
				}
				LOG.info("Upgraded: " + dest.getClass().getName());
				db.add(dest);
			}
			db.commit();
		} catch (SQLException e) {
			LOG.error(e);
			return;
		}		
	}

	private Map<String, Method> buildSetterMap(Sql conn, String entityKey) {
		Map<String, Method> setterMap = new HashMap<String, Method>();
		try {
			Object firstRow = conn.firstRow("SELECT * FROM " + entityKey);
			if(firstRow == null) {
				LOG.error("Unable to find anything in table: " + entityKey);
				return null;
			}
			if(firstRow instanceof Map) {
				Set<String> columnNames = ((Map) firstRow).keySet();
				Class definingClass = entityMap.get(entityKey);
				Field[] fields = definingClass.getDeclaredFields();
				//special case. Do this better.
				addToSetterMap(setterMap, columnNames, definingClass, fields);				
				Class superClass = entityMap.get(entityKey).getSuperclass();				
				while(superClass.isAnnotationPresent(MappedSuperclass.class)) {
					superClass = entityMap.get(entityKey).getSuperclass();
					Field[] superFields = superClass.getDeclaredFields();
					addToSetterMap(setterMap, columnNames, superClass, superFields);
					superClass = superClass.getSuperclass();
					//nothing to see here (otherwise we loop forever).
					if(superClass.equals(AbstractPersistent.class))
						break;
				}
			}
		} catch (SQLException e) {
			LOG.error(e);
		}
		return setterMap;
	}

	private void addToSetterMap(Map<String, Method> setterMap,
			Set<String> columnNames, Class definingClass, Field[] fields) {
		for(String column : columnNames) {
			for(Field f : fields) {
				if(f.isAnnotationPresent(Column.class)) {
					Column annotClass = (Column)f.getAnnotation(Column.class);						
					if(((String)column).toLowerCase().equals(annotClass.name().toLowerCase())) {
						String baseMethodName = f.getName( ).substring( 0, 1 ).toUpperCase( ) + f.getName( ).substring( 1 );
						try {
							Class[] classes = new Class[1];
							classes[0] = f.getType();
							Method setMethod = definingClass.getDeclaredMethod( "set" + baseMethodName, classes );
							setterMap.put(column, setMethod);
						} catch (SecurityException e) {
							LOG.error(e);
						} catch (NoSuchMethodException e) {
							LOG.error(e);
						}
						break;
					}
				}
			}
			if(setterMap.containsKey(column)) {
				LOG.info(column + " is set by: " + setterMap.get(column).getName());
			} else {
				LOG.info("No corresponding field found for: " + column + " in " + definingClass.getName());
			}
		}
	}

	private void buildEntityMap() {
		for (Class entity : entities) {
			if (entity.isAnnotationPresent(Table.class)) {
				Table annot = (Table)entity.getAnnotation(Table.class);
				entityMap.put(annot.name(), entity);
			}
		}
		//special cases
		entityMap.put("SNAPSHOT", Snapshot.class);
		entityMap.put("VOLUME", Volume.class);
	}

	static {
		//this is added by hand because there are special cases/entities that other upgrade scripts will process.
		//In the future, this should be discovered.
		//entities.add(BucketInfo.class);
		//entities.add(ObjectInfo.class);
		//entities.add(GrantInfo.class);
		entities.add(MetaDataInfo.class);
		entities.add(ImageCacheInfo.class);
		entities.add(TorrentInfo.class);
		entities.add(WalrusSnapshotInfo.class);
		entities.add(WalrusInfo.class);
		entities.add(WalrusStatsInfo.class);

		entities.add(VolumeInfo.class);
		entities.add(SnapshotInfo.class);
		entities.add(AOEMetaInfo.class);
		entities.add(AOEVolumeInfo.class);
		entities.add(ISCSIMetaInfo.class);
		entities.add(ISCSIVolumeInfo.class);
		entities.add(LVMVolumeInfo.class);
		entities.add(StorageInfo.class);
		entities.add(StorageStatsInfo.class);

		entities.add(ARecordInfo.class);
		entities.add(CNAMERecordInfo.class);
		entities.add(SOARecordInfo.class);
		entities.add(NSRecordInfo.class);
		entities.add(ZoneInfo.class);

		entities.add(com.eucalyptus.config.System.class);
		entities.add(ClusterConfiguration.class);
		entities.add(StorageControllerConfiguration.class);
		entities.add(WalrusConfiguration.class);
		entities.add(SystemConfiguration.class);

		entities.add(ImageInfo.class);
		entities.add(Address.class);
		entities.add(ClusterInfo.class);

		entities.add(Counters.class);
	}
}