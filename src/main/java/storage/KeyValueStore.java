package storage;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.Long.parseLong;

public class KeyValueStore {
    private final ConcurrentHashMap<String,StoredValue> map = new ConcurrentHashMap<>();

    public void set(String key,String value){
        map.put(key,StoredValue.withoutExpiration(value));
    }

    public String get(String key){
         StoredValue result = map.computeIfPresent(key, (k,v)->{
            if(v.isExpired(System.currentTimeMillis())){
                return null;
            }
            return v;
        } );
        return result == null ? null : result.value();
    }

        public long incr (String key){
          return Long.parseLong(incrementWithState(key).value());
        }

    public boolean del(String key){
        AtomicBoolean deleted = new AtomicBoolean(false);
        map.computeIfPresent(key,(k,v)->{
            long currentTime = System.currentTimeMillis();
            if(!v.isExpired(currentTime)){
                deleted.set(true);
            }
            return null;
        });
        return deleted.get();
    }

    public long decr(String key){
      return Long.parseLong(decrementWithState(key).value());
    }

    public boolean setnx(String key,String value){
        AtomicBoolean result = new AtomicBoolean(false);
        map.compute(key,(k,v)->{
           long now = System.currentTimeMillis();
           if(v== null || v.isExpired(now)) {
               result.set(true);
               return StoredValue.withoutExpiration(value);
           }else{
               result.set(false);
               return v;
           }
       });
       return result.get();
    }

    public boolean expire(String key,long seconds){
        return expireWithDeadline(key,seconds).success();
    }
        public long ttl(String key){
            StoredValue result = map.computeIfPresent(key,(k,v)->{
                long now = System.currentTimeMillis();
                if(v.isExpired(now)){
                    return null;
                }
                return v;
            });
            if(result == null){
                return -2;
            }
            if(result.expiresAt() == Long.MAX_VALUE){
                return -1;
            }
            long remainingtime = result.expiresAt()- System.currentTimeMillis();
            if(remainingtime <= 0){
                return -2;
            }
            return remainingtime/1000;
        }

        public boolean expireAt(String key, long deadLineMillis){
            AtomicBoolean result = new AtomicBoolean(false);
            if(deadLineMillis >= Long.MAX_VALUE){
                throw new IllegalArgumentException("limit exceeded");
            }
            map.computeIfPresent(key,(k,v)->{
                long now = System.currentTimeMillis();

                if(v.isExpired(now)){
                    return null;
                }
                if(deadLineMillis <=now){
                    result.set(true);
                    return null;
                }
                result.set(true);
                return new StoredValue(v.value(),deadLineMillis);
            });
            return result.get();
        }

        public ExpireResult expireWithDeadline(String key, long seconds){
           long now = System.currentTimeMillis();
           long deadline ;
           if(seconds<=0){
               deadline = now ;
           }else {
               long millSecs = Math.multiplyExact(seconds, 1000);
               deadline = Math.addExact(now, millSecs);
           }
           boolean success = expireAt(key,deadline);
           return new ExpireResult(success, deadline);
        }

        public StoredValue incrementWithState(String key){
            StoredValue Existing = map.compute(key,(k,v)->{
                long now = System.currentTimeMillis();
                if( v==null || v.isExpired(now)){
                    return StoredValue.withoutExpiration("1");
                }
                long numericValue = parseLong(v.value());
                long updated = Math.addExact(numericValue,1);
                return new StoredValue(Long.toString(updated),v.expiresAt());
            });
            return Existing;
        }

        public StoredValue decrementWithState(String key){
            StoredValue Existing = map.compute(key , (k,v)->{
                long now = System.currentTimeMillis();
                if(v== null || v.isExpired(now)){
                    return StoredValue.withoutExpiration("-1");
                }
                long numericValue = parseLong(v.value());
                long updated = Math.subtractExact(numericValue,1);
                return new StoredValue(Long.toString(updated),v.expiresAt());
            });
            return Existing;
        }

        public void setAt(String key , String value , long deadlineMillis){
            long now = System.currentTimeMillis();
             if(deadlineMillis >= Long.MAX_VALUE){
                throw new IllegalArgumentException("limit exceeded");
            }
            else if(deadlineMillis > now){
                map.put(key,new StoredValue(value,deadlineMillis));
            }else{
                map.remove(key);
             }
        }
}



