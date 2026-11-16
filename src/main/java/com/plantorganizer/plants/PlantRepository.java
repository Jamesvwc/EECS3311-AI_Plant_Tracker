package com.plantorganizer.plants;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.plantorganizer.common.ForbiddenException;
import com.plantorganizer.common.NotFoundException;
import com.plantorganizer.identification.PlantImage;

/**
 * Stage 1 PlantRepository: the single place that saves and loads owned plants,
 * their notes, photos and health records, and desired plants. It wraps the
 * Spring Data interfaces (which generate the SQL), so callers keep the Stage 1
 * method names (save, findAll, findById, saveNote, savePhoto, ...).
 */
@Repository
@Transactional
public class PlantRepository {

    private final OwnedPlantJpaRepository plants;
    private final PlantNoteJpaRepository notes;
    private final PlantPhotoJpaRepository photos;
    private final HealthRecordJpaRepository healthRecords;
    private final DesiredPlantJpaRepository desired;
    private final Clock clock;

    public PlantRepository(OwnedPlantJpaRepository plants, PlantNoteJpaRepository notes,
                           PlantPhotoJpaRepository photos, HealthRecordJpaRepository healthRecords,
                           DesiredPlantJpaRepository desired, Clock clock) {
        this.plants = plants;
        this.notes = notes;
        this.photos = photos;
        this.healthRecords = healthRecords;
        this.desired = desired;
        this.clock = clock;
    }

    // ---- owned plants (F08) ----

    public OwnedPlant save(OwnedPlant plant) {
        return plants.save(plant);
    }

    @Transactional(readOnly = true)
    public List<OwnedPlant> findAll(long ownerId) {
        return plants.findByOwnerIdOrderByAddedAtDesc(ownerId);
    }

    @Transactional(readOnly = true)
    public List<OwnedPlant> findAllOwners() {
        return plants.findAll();
    }

    @Transactional(readOnly = true)
    public OwnedPlant findById(long id) {
        return plants.findById(id).orElseThrow(() -> new NotFoundException("Plant " + id + " does not exist."));
    }

    /** Loads a plant and checks that it belongs to the given user. */
    @Transactional(readOnly = true)
    public OwnedPlant findOwned(long ownerId, long plantId) {
        OwnedPlant plant = findById(plantId);
        if (plant.getOwnerId() != ownerId) {
            throw new ForbiddenException("That plant belongs to another user.");
        }
        return plant;
    }

    @Transactional(readOnly = true)
    public List<OwnedPlant> findByIds(Collection<Long> ids) {
        return plants.findByIdIn(ids);
    }

    public void delete(OwnedPlant plant) {
        notes.deleteByPlantId(plant.getId());
        photos.deleteByPlantId(plant.getId());
        healthRecords.deleteByPlantId(plant.getId());
        plants.delete(plant);
    }

    // ---- notes (F05 / F08) ----

    public PlantNote saveNote(long plantId, PlantNote.Kind kind, String text) {
        return notes.save(new PlantNote(plantId, kind, text, LocalDateTime.now(clock)));
    }

    @Transactional(readOnly = true)
    public List<PlantNote> findNotes(long plantId) {
        return notes.findByPlantIdOrderByCreatedAtDesc(plantId);
    }

    public void deleteNote(long plantId, long noteId) {
        notes.findById(noteId).filter(n -> n.getPlantId() == plantId).ifPresent(notes::delete);
    }

    // ---- photos and health records (F05) ----

    public PlantPhoto savePhoto(long plantId, PlantImage image) {
        return photos.save(new PlantPhoto(plantId, image.data(), image.contentType(), LocalDateTime.now(clock)));
    }

    @Transactional(readOnly = true)
    public Optional<PlantPhoto> findPhoto(long photoId) {
        return photos.findById(photoId);
    }

    @Transactional(readOnly = true)
    public Optional<PlantPhoto> latestPhoto(long plantId) {
        return photos.findByPlantIdOrderByUploadedAtDesc(plantId).stream().findFirst();
    }

    public HealthRecord saveHealthRecord(HealthRecord record) {
        return healthRecords.save(record);
    }

    @Transactional(readOnly = true)
    public List<HealthRecord> findHealthRecords(long plantId) {
        return healthRecords.findByPlantIdOrderByCreatedAtDesc(plantId);
    }

    @Transactional(readOnly = true)
    public HealthRecord findHealthRecord(long recordId) {
        return healthRecords.findById(recordId)
                .orElseThrow(() -> new NotFoundException("Health record " + recordId + " does not exist."));
    }

    // ---- desired plants (F09) ----

    public DesiredPlant saveDesired(DesiredPlant plant) {
        return desired.save(plant);
    }

    @Transactional(readOnly = true)
    public List<DesiredPlant> findDesired(long ownerId) {
        return desired.findByOwnerIdOrderByAddedAtDesc(ownerId);
    }

    @Transactional(readOnly = true)
    public DesiredPlant findDesiredById(long ownerId, long id) {
        DesiredPlant plant = desired.findById(id)
                .orElseThrow(() -> new NotFoundException("Wishlist item " + id + " does not exist."));
        if (plant.getOwnerId() != ownerId) {
            throw new ForbiddenException("That wishlist item belongs to another user.");
        }
        return plant;
    }

    public void deleteDesired(DesiredPlant plant) {
        desired.delete(plant);
    }
}
