package ru.morkamo.enterprisetester.extension;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.Optional;

@NoRepositoryBean
public interface JpaRepositoryEnhanced<T, ID>
        extends JpaRepository<T, ID> {

    /*default User getUserByIdOrException(Long id){
        Optional<User> _user = findById(id);
        if (_user.isEmpty())
            throw new IllegalStateException("User with id " + id + " does not exist");
        return _user.get();
    }*/
}
