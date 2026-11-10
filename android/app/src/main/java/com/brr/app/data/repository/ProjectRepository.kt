package com.brr.app.data.repository

import com.brr.app.data.db.BrrDatabase
import com.brr.app.data.db.ProjectEntity
import com.brr.app.data.db.ProjectWithCounts
import kotlinx.coroutines.flow.Flow

class ProjectRepository(db: BrrDatabase) {
    private val dao = db.projectDao()

    fun observeWithCounts(): Flow<List<ProjectWithCounts>> = dao.observeWithCounts()
    fun observeAll(): Flow<List<ProjectEntity>> = dao.observeAll()
    fun observe(id: Long): Flow<ProjectEntity?> = dao.observe(id)

    suspend fun create(name: String, color: Long): Long =
        dao.insert(ProjectEntity(name = name.trim(), color = color, createdAt = System.currentTimeMillis()))

    suspend fun update(p: ProjectEntity) = dao.update(p.copy(name = p.name.trim()))
    suspend fun delete(id: Long) = dao.delete(id)

    /** Retrouve un projet par son nom (insensible à la casse) ou le crée. */
    suspend fun findOrCreate(name: String): Long =
        dao.findByName(name.trim())?.id ?: create(name, PALETTE[(name.hashCode() and 0x7fffffff) % PALETTE.size])

    companion object {
        val PALETTE: List<Long> = listOf(
            0xFFFF2D3D, 0xFFFF6A1A, 0xFFFFB020, 0xFF2ECC71, 0xFF1ABC9C,
            0xFF4C8DFF, 0xFF8B5CF6, 0xFFEC4899, 0xFF94A3B8,
        )
    }
}
