package cn.devcxl.photosync.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import cn.devcxl.photosync.data.entity.PhotoEntity;
import kotlinx.coroutines.flow.Flow;

import java.util.List;

@Dao
public interface PhotoDao {
    @Query("SELECT * FROM photos ORDER BY createdAt ASC")
    Flow<List<PhotoEntity>> getAllFlow();

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    Long insert(PhotoEntity entity);

    @Query("DELETE FROM photos")
    void clear();
}
