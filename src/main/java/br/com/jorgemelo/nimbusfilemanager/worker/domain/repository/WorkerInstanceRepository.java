package br.com.jorgemelo.nimbusfilemanager.worker.domain.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.worker.domain.model.WorkerInstance;

public interface WorkerInstanceRepository extends JpaRepository<WorkerInstance, String> {

	/**
	 * The instances heard from recently enough to be counted as alive. A list
	 * rather than a count, because two rows answer a question one number cannot:
	 * Nimbus starts one worker, so a second live instance means something started
	 * a worker it should not have, and that is worth being able to see.
	 */
	List<WorkerInstance> findByLastSeenAtAfter(LocalDateTime threshold);

	/**
	 * Rows of instances long gone. Kept out of the read above by the same
	 * threshold, and removed on a much longer one - a worker that died holds no
	 * resource here, and its last row is the only evidence of when it was last
	 * seen.
	 */
	@Transactional
	@Modifying
	@Query("delete from WorkerInstance instance where instance.lastSeenAt < :threshold")
	int deleteByLastSeenAtBefore(@Param("threshold") LocalDateTime threshold);
}